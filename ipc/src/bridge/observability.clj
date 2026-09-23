(ns bridge.observability
  "Explicit loopback-only observer. Scrapes never generate provider traffic."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [bridge.metrics :as metrics])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress URI]
           [java.util.concurrent Executors TimeUnit TimeoutException ExecutionException Callable]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn initial-readiness [options]
  {:available (contains? #{nil :openai-compatible} (:provider options))
   :configured false :verified false :checked-at nil :reason :not-configured})

(defn current-readiness [{:keys [readiness options]}]
  (let [ready @readiness]
    (if (and (:verified ready) (> (- (System/currentTimeMillis) (:checked-at ready))
                                  (get options :verification-ttl-ms 300000)))
      (assoc ready :verified false :reason :expired) ready)))

(defn remaining-ms [deadline]
  (let [remaining (long (/ (- deadline (System/nanoTime)) 1000000))]
    (when (or (not (pos? remaining)) (.isInterrupted (Thread/currentThread)))
      (throw (TimeoutException. "Probe deadline expired")))
    remaining))

(defn http! [observer deadline path body]
  (let [{:keys [options client]} observer
        remaining (remaining-ms deadline)
        builder (-> (HttpRequest/newBuilder (URI. (str (str/replace (:base-url options) #"/$" "") path)))
                    (.timeout (Duration/ofMillis remaining)))
        _ (when-let [bearer (when-let [read-bearer (:read-bearer options)] (read-bearer))]
            (.header builder "Authorization" (str "Bearer " bearer)))
        _ (if body (do (.header builder "Content-Type" "application/json")
                       (.POST builder (HttpRequest$BodyPublishers/ofString (json/write-str body))))
              (.GET builder))
        remaining (remaining-ms deadline)
        _ (.timeout builder (Duration/ofMillis remaining))
        pending (.sendAsync ^HttpClient client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    (try
      (let [response (.get pending remaining TimeUnit/MILLISECONDS)
            status (.statusCode response)]
        (when-not (<= 200 status 299)
          (throw (ex-info "Probe failed" {:reason (cond (#{401 403} status) :authentication
                                                        (= 429 status) :rate-limited :else :upstream)})))
        (try (json/read-str (.body response) :key-fn keyword)
             (catch Exception _ (throw (ex-info "Invalid probe response" {:reason :invalid-response})))))
      (finally (.cancel pending true)))))

(defn probe! [{:keys [options] :as observer}]
  (let [initial (initial-readiness options)
        deadline (+ (System/nanoTime) (* 1000000 (get options :timeout-ms 1000)))]
    (if-not (and (:available initial) (:configured? options) (string? (:base-url options))
                 (not (str/blank? (:model options))))
      initial
      (let [configured (or (:visibility observer) (atom false))]
        (try
          (let [models (http! observer deadline "/models" nil)]
            (if-not (some #(= (:model options) (:id %)) (:data models))
              (assoc initial :reason :not-visible)
              (do (reset! configured true)
                  (let [response (http! observer deadline "/chat/completions"
                                        {:model (:model options) :messages [{:role "user" :content "Reply OK."}]
                                         :max_completion_tokens 128 :stream false})]
                    (when-not (let [content (get-in response [:choices 0 :message :content])]
                                (and (string? content) (not (str/blank? content))))
                      (throw (ex-info "Invalid probe response" {:reason :invalid-response}))))
                  (assoc initial :configured true :verified true :reason :ok))))
          (catch Throwable error
            (let [cause (if (instance? ExecutionException error) (.getCause error) error)]
              (assoc initial :configured @configured
                     :reason (or (#{:authentication :rate-limited :upstream :invalid-response} (:reason (ex-data cause)))
                                 (when (or (instance? TimeoutException cause)
                                           (instance? java.net.http.HttpTimeoutException cause)) :timeout)
                                 :unreachable)))))))))

(defn refresh! [{:keys [readiness refreshing? closed? options] :as observer}]
  (if (or @closed? (not (compare-and-set! refreshing? false true)))
    (current-readiness observer)
    (try
      (if (and (:checked-at @readiness)
               (< (- (System/currentTimeMillis) (:checked-at @readiness)) (get options :refresh-ms 60000)))
        (current-readiness observer)
        (let [visibility (atom false)
              pending (.submit ^java.util.concurrent.ExecutorService (:executor observer)
                               ^Callable #(probe! (assoc observer :visibility visibility)))
              result (try (.get pending (long (get options :timeout-ms 1000)) TimeUnit/MILLISECONDS)
                          (catch TimeoutException _ (assoc (initial-readiness options) :configured @visibility :reason :timeout))
                          (finally (.cancel pending true)))]
          (reset! readiness (assoc result :checked-at (System/currentTimeMillis)))))
      (finally (reset! refreshing? false)))))

(defn render [state ready]
  (str (metrics/render state) "# TYPE bridge_active_calls gauge\nbridge_active_calls " (count (:active state)) "\n"
       "# TYPE bridge_queue_depth gauge\nbridge_queue_depth " (count (:queue state)) "\n"
       "# TYPE bridge_verification_status gauge\nbridge_verification_status " (if (:verified ready) 1 0) "\n"))

(defn bounded-snapshot [{:keys [options executor]} snapshot]
  (let [pending (.submit ^java.util.concurrent.ExecutorService executor ^Callable snapshot)]
    (try (.get pending (long (get options :timeout-ms 1000)) TimeUnit/MILLISECONDS)
         (finally (.cancel pending true)))))

(defn start! [{:keys [port snapshot] :or {port 0} :as options}]
  (when-not (fn? snapshot)
    (throw (ex-info "Observer requires an explicit module snapshot function" {:type ::invalid-configuration})))
  (doseq [key [:timeout-ms :refresh-ms :verification-ttl-ms]]
    (when-not (pos-int? (get options key 1))
      (throw (ex-info "Observer interval must be a positive integer" {:type ::invalid-configuration}))))
  (let [options (if-let [file (:bearer-file options)]
                  (let [reader (requiring-resolve 'bridge.module/read-bearer)]
                    (assoc options :read-bearer #(reader file)))
                  options)
        ready (atom (initial-readiness options))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)
        executor (Executors/newVirtualThreadPerTaskExecutor)
        observer {:server server :executor executor :port (.getPort (.getAddress server))
                  :readiness ready :options options :refreshing? (atom false) :closed? (atom false)
                  :client (-> (HttpClient/newBuilder) (.executor executor) .build)}]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [path (.getPath ^URI (.getRequestURI exchange))
                              method (.getRequestMethod exchange)
                              [status body] (try
                                              (cond
                                                (and (= path "/ready") (= method "GET")) [200 (json/write-str (current-readiness observer))]
                                                (and (= path "/metrics") (= method "GET")) [200 (render (bounded-snapshot observer snapshot) (current-readiness observer))]
                                                (and (= path "/refresh") (= method "POST")) [200 (json/write-str (refresh! observer))]
                                                :else [404 "not found\n"])
                                              (catch Throwable _ [503 "observer unavailable\n"]))
                              bytes (.getBytes body "UTF-8")]
                          (.set (.getResponseHeaders exchange) "Content-Type" (if (= path "/metrics") "text/plain; version=0.0.4; charset=utf-8" "application/json"))
                          (.sendResponseHeaders exchange status (alength bytes))
                          (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.setExecutor server executor)
    (.start server)
    observer))

(defn stop! [{:keys [server executor client closed?]}]
  (reset! closed? true)
  (.stop ^HttpServer server 0)
  (.shutdownNow ^HttpClient client)
  (.shutdownNow ^java.util.concurrent.ExecutorService executor))
