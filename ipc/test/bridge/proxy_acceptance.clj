(ns bridge.proxy-acceptance
  "Compose the shipped adapter with a real proxy and counted fake provider."
  (:require [clojure.data.json :as json]
            [clojure.test :as test]
            [bridge.ipc-test :as ipc])
  (:import [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]
           [java.time Duration]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn free-port [] (with-open [socket (ServerSocket. 0)] (.getLocalPort socket)))

(def pinned-proxy-sha256 "7212d39890dac46fac10d75f8029d8c377fdcc75798d5dcecefffc90b987d0a9")

(defn verify-proxy! [binary]
  (let [bytes (Files/readAllBytes (Paths/get binary (make-array String 0)))
        digest (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))]
    (when-not (= pinned-proxy-sha256 digest)
      (throw (ex-info "Expected the pinned CLIProxyAPI 7.3.15 fixture binary" {:sha256 digest})))
    (println "Proxy SHA256:" digest)))

(defn models [port]
  (with-open [client (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofMillis 500)) .build)]
    (let [request (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/v1/models")))
                      (.timeout (Duration/ofSeconds 1))
                      (.header "Authorization" "Bearer ipc-fixture") .GET .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode response))
        (set (map :id (:data (json/read-str (.body response) :key-fn keyword))))))))

(defn start-proxy! [binary directory provider-port proxy-port]
  (let [config (.resolve directory "config.json")
        auth (.resolve directory "auth")
        _ (Files/createDirectory auth (make-array FileAttribute 0))
        settings {:host "127.0.0.1" :port proxy-port :auth-dir (str auth)
                  :api-keys ["ipc-fixture"] :commercial-mode true
                  :logging-to-file false :request-log false :request-retry 0
                  :max-retry-credentials 1 :max-retry-interval 0
                  :remote-management {:disable-control-panel true}
                  :openai-compatibility [{:name "acceptance"
                                          :base-url (str "http://127.0.0.1:" provider-port "/v1")
                                          :api-key-entries [{:api-key "fixture-key"}]
                                          :models [{:name "fixture" :alias "fixture"}
                                                   {:name "spike-nonexistent-model" :alias "spike-nonexistent-model"}]}]}
        _ (spit (str config) (json/write-str settings :escape-slash false))
        builder (doto (ProcessBuilder. ^java.util.List [binary "-config" (str config)])
                  (.directory (.toFile directory))
                  (.redirectErrorStream true)
                  (.redirectOutput (.toFile (.resolve directory "proxy.log"))))]
    (.clear (.environment builder))
    (.put (.environment builder) "HOME" (str directory))
    (.put (.environment builder) "PATH" "/usr/bin:/bin")
    (.start builder)))

(test/deftest completed-text-replay-adds-zero-provider-requests
  (let [{:keys [client calls]} ipc/*context*
        request {:prompt "fixture" :call-id "composed-text-replay"}
        before (count @calls)
        original (ipc/streamed-result client request)
        original-count (count @calls)
        replayed (ipc/streamed-result client request)]
    (test/is (= 1 (- original-count before)))
    (test/is (= "one two" (get-in original [:result :text])))
    (test/is (= ["one" " two"] (:chunks original)))
    (test/is (= {:input 3 :output 2 :total 5} (get-in original [:result :usage])))
    (test/is (= (select-keys original [:result :chunks]) (select-keys replayed [:result :chunks])))
    (test/is (= 0 (- (count @calls) original-count)))))

(def cases
  [#'completed-text-replay-adds-zero-provider-requests
   #'ipc/nested-stream-reaches-client-in-order
   #'ipc/rama-retry-replays-completed-call
   #'ipc/concurrent-identical-calls-share-stream-and-upstream
   #'ipc/different-in-flight-request-cannot-reuse-identity
   #'ipc/reused-identity-returns-typed-conflict
   #'ipc/completed-tool-exchange-replays-serialized-tool-calls-and-chunks
   #'ipc/saved-trace-contains-model-response-and-usage
   #'ipc/default-admission-is-ten-active-fifty-fifo-and-typed-overload
   #'ipc/cancellation-completes-waiters-without-releasing-a-live-transport-slot
   #'ipc/closing-subscription-does-not-cancel-model-call
   #'ipc/provider-error-propagates-through-agent
   #'ipc/model-timeout-propagates-through-agent
   #'ipc/observer-counts-global-model-outcomes])

(defn stop-proxy! [process]
  (when-let [child (first (swap-vals! process (constantly nil)))]
    (.destroy ^Process child)
    (when-not (.waitFor ^Process child 10 TimeUnit/SECONDS)
      (.destroyForcibly ^Process child)
      (when-not (.waitFor ^Process child 10 TimeUnit/SECONDS)
        (throw (ex-info "Fixture proxy did not terminate" {:pid (.pid ^Process child)}))))))

(defn run-acceptance! [binary process]
  (verify-proxy! binary)
  (let [directory (Files/createTempDirectory "bridge-proxy-acceptance-" (make-array FileAttribute 0))
        port (free-port)
        original-server ipc/fake-server]
    (println "Private fixture directory:" (str directory))
    (try
      (with-redefs [ipc/fake-server
                    (fn [calls release activity]
                      (let [server (original-server calls release activity)]
                        (try
                          (reset! process (start-proxy! binary directory (.getPort (.getAddress server)) port))
                          (when-not (ipc/eventually #(try (= #{"fixture" "spike-nonexistent-model"} (models port))
                                                          (catch Exception _ false)))
                            (throw (ex-info "Isolated proxy did not become ready" {})))
                          server
                          (catch Throwable error
                            (.stop server 0)
                            (.close ^java.util.concurrent.ExecutorService (.getExecutor server))
                            (throw error)))))]
        (ipc/with-ipc {:base-url (str "http://127.0.0.1:" port "/v1")}
          (fn []
            (doseq [case cases]
              (let [before (count @(:calls ipc/*context*))]
                (test/test-var case)
                (let [name (:name (meta case))
                      observed (- (count @(:calls ipc/*context*)) before)
                      expected (get {'completed-tool-exchange-replays-serialized-tool-calls-and-chunks 2
                                     'default-admission-is-ten-active-fifty-fifo-and-typed-overload 60
                                     'cancellation-completes-waiters-without-releasing-a-live-transport-slot 11
                                     ;; REPLAY.md: terminal failures are not successful replay entries.
                                     'provider-error-propagates-through-agent 3
                                     'model-timeout-propagates-through-agent 3}
                                    name 1)]
                  (test/is (= expected observed) (str name " final provider HTTP request count"))
                  (println "PROVIDER-REQUESTS" name observed)))))))
      (finally
        (stop-proxy! process)
        (println "Proxy stopped; only isolated fixture configuration/auth used.")))))

(defn -main [binary]
  (let [process (atom nil)
        watchdog (doto (Thread.
                        (fn []
                          (try
                            (Thread/sleep 600000)
                            (binding [*out* *err*] (println "Composed acceptance exceeded ten minutes"))
                            (try (stop-proxy! process) (finally (System/exit 124)))
                            (catch InterruptedException _ nil))))
                   (.setDaemon true)
                   (.start))
        status (try
                 (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                   (try (run-acceptance! binary process)
                        (catch Throwable error
                          (test/do-report {:type :error :message "Composed fixture failed" :expected :success :actual error})))
                   (test/do-report (assoc @test/*report-counters* :type :summary))
                   (let [{:keys [fail error]} @test/*report-counters*]
                     (if (zero? (+ fail error)) 0 1)))
                 (finally (.interrupt watchdog)))]
    (shutdown-agents)
    (System/exit status)))
