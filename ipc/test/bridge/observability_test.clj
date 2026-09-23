(ns bridge.observability-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [bridge.observability :as obs])
  (:import [java.net HttpURLConnection URI]))

(defn request [port path method]
  (let [^HttpURLConnection c (.openConnection (.toURL (URI. (str "http://127.0.0.1:" port path))))]
    (.setRequestMethod c method)
    {:status (.getResponseCode c) :body (slurp (.getInputStream c))}))

(deftest readiness-is-explicit-private-and-sanitized
  (let [server (obs/start! {:snapshot (constantly nil)})]
    (try
      (let [ready (json/read-str (:body (request (:port server) "/ready" "GET")) :key-fn keyword)]
        (is (= true (:available ready)))
        (is (= false (:configured ready)))
        (is (= false (:verified ready)))
        (is (= "not-configured" (:reason ready))))
      (is (re-find #"bridge_verification_status 0" (:body (request (:port server) "/metrics" "GET"))))
      (finally (obs/stop! server)))))

(deftest refresh-observes-visibility-and-clears-stale-success
  (let [mode (atom :ok) calls (atom [])
        fake (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext fake "/v1/" (reify com.sun.net.httpserver.HttpHandler
                                  (handle [_ e]
                                    (let [path (.getPath (.getRequestURI e))]
                                      (swap! calls conj path)
                                      (when (= :timeout @mode) (Thread/sleep 300))
                                      (let [body (.getBytes (if (.endsWith path "/models")
                                                              (json/write-str {:data (if (= :missing @mode) [] [{:id "private-model"}])})
                                                              "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}") "UTF-8")]
                                        (try (.sendResponseHeaders e (if (= :error @mode) 401 200) (alength body))
                                             (with-open [out (.getResponseBody e)]
                                               (when (= :slow-body @mode)
                                                 (.write out body 0 1) (.flush out) (Thread/sleep 300))
                                               (.write out body))
                                             (catch java.io.IOException _ nil)))))))
    (.start fake)
    (let [server (obs/start! {:snapshot (constantly nil) :configured? true :model "private-model"
                              :base-url (str "http://127.0.0.1:" (.getPort (.getAddress fake)) "/v1")
                              :timeout-ms 100 :refresh-ms 20 :verification-ttl-ms 50})]
      (try
        (is (empty? @calls))
        (let [result (obs/refresh! server)]
          (is (:configured result)) (is (:verified result)) (is (integer? (:checked-at result))))
        (obs/refresh! server)
        (is (= 2 (count @calls)))
        (Thread/sleep 60)
        (is (false? (:verified (json/read-str (:body (request (:port server) "/ready" "GET")) :key-fn keyword))))
        (reset! mode :missing)
        (is (= :not-visible (:reason (obs/refresh! server))))
        (Thread/sleep 25)
        (reset! mode :error)
        (is (= :authentication (:reason (obs/refresh! server))))
        (Thread/sleep 25)
        (reset! mode :slow-body)
        (let [start (System/nanoTime) result (obs/refresh! server)]
          (is (= :timeout (:reason result))) (is (false? (:verified result)))
          (is (< (/ (- (System/nanoTime) start) 1e6) 250)))
        (finally (obs/stop! server) (.stop fake 0))))))

(deftest failed-completion-remains-configured-and-refresh-is-single-flight
  (let [gate (promise) arrived (promise) mode (atom :blocked) calls (atom 0)
        fake (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext fake "/v1/" (reify com.sun.net.httpserver.HttpHandler
                                  (handle [_ e]
                                    (swap! calls inc)
                                    (let [models? (.endsWith (.getPath (.getRequestURI e)) "/models")]
                                      (when (and (not models?) (= :blocked @mode))
                                        (deliver arrived true) (deref gate 2000 nil))
                                      (let [body (.getBytes (if models? "{\"data\":[{\"id\":\"fixture\"}]}" "{\"choices\":[{}]}") "UTF-8")]
                                        (try (.sendResponseHeaders e 200 (alength body))
                                             (with-open [out (.getResponseBody e)] (.write out body))
                                             (catch java.io.IOException _ nil)))))))
    (.start fake)
    (let [options {:configured? true :model "fixture" :snapshot (constantly nil)
                   :base-url (str "http://127.0.0.1:" (.getPort (.getAddress fake)) "/v1")
                   :timeout-ms 500 :refresh-ms 1}
          server (obs/start! options)
          unsupported (obs/start! (assoc options :provider :unsupported))]
      (try
        (is (false? (:available (obs/refresh! unsupported)))) (is (zero? @calls))
        (let [pending (future (obs/refresh! server))]
          (is (= true (deref arrived 2000 :timeout)))
          (dotimes [_ 4] (obs/refresh! server))
          (is (= 2 @calls))
          (let [ready @pending]
            (is (:available ready)) (is (:configured ready))
            (is (false? (:verified ready))) (is (= :timeout (:reason ready)))))
        (deliver gate true) (reset! mode :invalid) (Thread/sleep 20)
        (is (= :invalid-response (:reason (obs/refresh! server))))
        (finally (deliver gate true) (obs/stop! server) (obs/stop! unsupported) (.stop fake 0))))))

(deftest unavailable-snapshot-is-bounded-and-sanitized
  (let [server (obs/start! {:timeout-ms 50 :snapshot #(do (Thread/sleep 5000) nil)})
        ^HttpURLConnection c (.openConnection (.toURL (URI. (str "http://127.0.0.1:" (:port server) "/metrics"))))]
    (try
      (is (= 503 (.getResponseCode c)))
      (is (= "observer unavailable\n" (slurp (.getErrorStream c))))
      (finally (obs/stop! server)))))

(deftest observer-requires-an-explicit-module-snapshot
  (is (thrown? clojure.lang.ExceptionInfo (obs/start! {}))))

(deftest expired-credential-read-cannot-dispatch-a-late-probe
  (let [calls (atom 0) finished (promise)
        fake (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext fake "/" (reify com.sun.net.httpserver.HttpHandler
                               (handle [_ e] (swap! calls inc) (.sendResponseHeaders e 500 -1) (.close e))))
    (.start fake)
    (let [server (obs/start! {:snapshot (constantly nil) :configured? true :model "fixture" :timeout-ms 50
                              :base-url (str "http://127.0.0.1:" (.getPort (.getAddress fake)) "/v1")
                              :read-bearer #(do (try (Thread/sleep 1000)
                                                     (catch InterruptedException _ (Thread/sleep 80)))
                                                (deliver finished true) "PRIVATE_SENTINEL")})]
      (try
        (is (= :timeout (:reason (obs/refresh! server))))
        (is (= true (deref finished 2000 :timeout)))
        (Thread/sleep 50)
        (is (zero? @calls))
        (finally (obs/stop! server) (.stop fake 0))))))
