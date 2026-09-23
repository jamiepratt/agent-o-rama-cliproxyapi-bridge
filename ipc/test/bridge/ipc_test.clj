(ns bridge.ipc-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [clojure.data.json :as json]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.langchain4j :as lc4j]
            [com.rpl.agent-o-rama.langchain4j.json :as schema]
            [com.rpl.rama :as rama]
            [bridge.replay :as replay]
            [com.rpl.rama.test :as rtest]
            [bridge.module :as module]
            [bridge.inspect :as inspect])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent TimeUnit Executors]))

(defn send-event! [out value]
  (.write out (.getBytes (str "data: " (if (string? value) value (json/write-str value)) "\n\n") "UTF-8"))
  (.flush out))

(defn fake-server [calls release]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/v1/chat/completions"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
               prompt (get-in request [:messages 0 :content])
               tool-result? (some #(= "tool" (:role %)) (:messages request))
               tool? (seq (:tools request))
               chunk {:id "fixture" :object "chat.completion.chunk" :created 1 :model "fixture"}
               deltas (cond (= prompt "generation-new") [{:content "new"}]
                            tool-result? [{:content "ping"}]
                            tool? [{:tool_calls [{:index 0 :id "call_fixture" :type "function"
                                                  :function {:name "spike_echo" :arguments "{\"value\":\"ping\"}"}}]}]
                            :else [{:content "one"} {:content " two"}])]
           (swap! calls conj request)
           (when (= "stall" prompt) (Thread/sleep 1000))
           (try
             (if (= "spike-nonexistent-model" (:model request))
               (let [body (.getBytes (json/write-str {:error {:message "fixture error" :type "invalid_request_error"}}) "UTF-8")]
                 (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                 (.sendResponseHeaders exchange 400 (count body))
                 (with-open [out (.getResponseBody exchange)] (.write out body)))
               (do
                 (.set (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
                 (.sendResponseHeaders exchange 200 0)
                 (with-open [out (.getResponseBody exchange)]
                   (doseq [[i delta] (map-indexed vector deltas)]
                     (send-event! out (assoc chunk :choices [{:index 0 :delta delta :finish_reason nil}]))
                     (when (and (#{"pause" "identity-race" "generation-first"} prompt) (zero? i)) (deref (get release prompt) 10000 nil)))
                   (send-event! out (assoc chunk :choices [{:index 0 :delta {} :finish_reason (if tool? "tool_calls" "stop")}]))
                   (send-event! out (assoc chunk :choices [] :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}))
                   (send-event! out "[DONE]"))))
             (catch java.io.IOException _ nil))))))
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    server))

(def ^:dynamic *context* nil)

(defn with-ipc [test-fn]
  (let [calls (atom []) release (promise) race-release (promise) generation-release (promise) server (fake-server calls {"pause" release "identity-race" race-release "generation-first" generation-release})]
    (try
      (with-open [ipc (rtest/create-ipc)]
        (let [config {:sweep-ms 50 :deadline-ms 100 :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")}
              mod (module/proxy-module config)]
          (rtest/launch-module! ipc mod {:tasks 2 :threads 2 :workers 2})
          (binding [*context* {:ipc ipc :config config :generation-release generation-release :module-name (rama/get-module-name mod) :calls calls :release release :race-release race-release
                               :client (aor/agent-client (aor/agent-manager ipc (rama/get-module-name mod)) "chat")}]
            (test-fn))))
      (finally (.stop server 0) (.close ^java.util.concurrent.ExecutorService (.getExecutor server))))))

(use-fixtures :once with-ipc)

(deftest nested-stream-reaches-client-in-order
  (let [{:keys [client]} *context*
        invoke (aor/agent-initiate client {:prompt "fixture"})
        complete (promise)]
    (with-open [_stream (aor/agent-stream client invoke "model"
                                          (fn [all _new _reset? done?] (when done? (deliver complete all))))]
      (let [result (aor/agent-result client invoke)]
        (is (= "one two" (:text result)))
        (is (= {:input 3 :output 2 :total 5} (:usage result))))
      (is (= ["one" " two"] (deref complete 10000 :timeout))))))

(deftest rama-retry-replays-completed-call
  (let [{:keys [client calls]} *context* before (count @calls)]
    (is (= "one two" (:text (aor/agent-invoke client {:prompt "fixture" :force-retry? true}))))
    (is (= 1 (- (count @calls) before)))))

(deftest tool-roundtrip-returns-executed-result-to-model
  (let [{:keys [client calls]} *context* before (count @calls)]
    (is (= "ping" (:text (aor/agent-invoke client {:prompt "fixture" :tool? true}))))
    (is (= 2 (- (count @calls) before)))
    (is (= {:role "tool" :tool_call_id "call_fixture" :content "ping"}
           (first (filter #(= "tool" (:role %)) (:messages (last @calls))))))))

(deftest model-timeout-propagates-through-agent
  (let [{:keys [client ipc module-name calls]} *context*
        invoke (aor/agent-initiate client {:prompt "stall" :timeout? true})]
    (is (thrown? Exception (aor/agent-result client invoke)))
    (is (some #(= "stall" (get-in % [:messages 0 :content])) @calls))
    (is (inspect/expected-failure? (inspect/model-operations ipc module-name client invoke) :timeout))))

(deftest saved-trace-contains-model-response-and-usage
  (let [{:keys [ipc module-name client]} *context*
        invoke (aor/agent-initiate client {:prompt "fixture"})]
    (aor/agent-result client invoke)
    (let [ops (inspect/model-operations ipc module-name client invoke)
          info (:info (first ops))]
      (is (= 1 (count ops)))
      (is (= "one two" (get info "response")))
      (is (= [3 2 5] (mapv info ["inputTokenCount" "outputTokenCount" "totalTokenCount"])))
      (is (number? (get info "firstTokenTimeMillis"))))))

(deftest closing-subscription-does-not-cancel-model-call
  (let [{:keys [client release]} *context*
        first-chunk (promise) callbacks (atom 0)
        invoke (aor/agent-initiate client {:prompt "pause"})
        stream (aor/agent-stream client invoke "model"
                                 (fn [_all new _reset? _done?]
                                   (swap! callbacks inc)
                                   (when (seq new) (deliver first-chunk true))))]
    (try
      (is (= true (deref first-chunk 10000 :timeout)))
      (.close stream)
      (let [before @callbacks]
        (deliver release true)
        (is (= "one two" (:text (aor/agent-result client invoke))))
        (is (= before @callbacks)))
      (finally (deliver release true) (.close stream)))))

(deftest provider-error-propagates-through-agent
  (let [{:keys [client ipc module-name calls]} *context*
        invoke (aor/agent-initiate client {:prompt "fixture" :model-name "spike-nonexistent-model"})]
    (is (thrown? Exception (aor/agent-result client invoke)))
    (is (some #(= "spike-nonexistent-model" (:model %)) @calls))
    (is (inspect/expected-failure? (inspect/model-operations ipc module-name client invoke) :http-400))))

(deftest expired-completions-are-removed-without-another-request
  (let [{:keys [client ipc module-name calls]} *context*
        state (rama/foreign-pstate ipc module-name "$$completed-calls")
        key "expiry/0"
        before (count @calls)
        original (with-redefs [replay/retention-ms 5000]
                   (aor/agent-invoke client {:prompt "fixture" :call-id "expiry"}))
        deadline (:expires-at (rama/foreign-select-one [key] state))]
    (is (number? deadline))
    (is (= original (aor/agent-invoke client {:prompt "fixture" :call-id "expiry"})))
    (is (= deadline (:expires-at (rama/foreign-select-one [key] state))))
    (is (nil? (loop [remaining 200]
                (let [entry (rama/foreign-select-one [key] state)]
                  (if (and entry (pos? remaining))
                    (do (Thread/sleep 50) (recur (dec remaining)))
                    entry)))))
    (is (= "new" (:text (aor/agent-invoke client {:prompt "generation-new" :call-id "expiry"}))))
    (is (= 2 (- (count @calls) before)))))

(deftest reused-identity-returns-typed-conflict
  (let [{:keys [client calls]} *context* before (count @calls)]
    (aor/agent-invoke client {:prompt "fixture" :call-id "conflict"})
    (is (= {:error {:type :bridge.replay/identity-conflict}}
           (try (aor/agent-invoke client {:prompt "different" :call-id "conflict"})
                (catch Exception _ :untyped-exception))))
    (is (= 1 (- (count @calls) before)))))

(deftest malformed-or-unsupported-request-is-rejected-before-upstream
  (let [{:keys [client calls]} *context* before (count @calls)]
    (doseq [request [{:prompt "fixture" :call-id 1}
                     {:prompt "fixture" :call-id ""}
                     {:prompt "fixture" :temperature 0.5}]]
      (is (= {:error {:type :bridge.replay/invalid-request}}
             (try (aor/agent-invoke client request) (catch Exception _ :untyped-exception)))))
    (is (= before (count @calls)))))

(deftest canonical-fingerprint-distinguishes-semantic-schema-types
  (let [request (fn [shape] (lc4j/chat-request ["fixture"]
                                               {:response-format (lc4j/json-response-format "result" shape)}))]
    (is (not= (replay/fingerprint (request (schema/string)) {})
              (replay/fingerprint (request (schema/boolean)) {})))))

(deftest different-in-flight-request-cannot-reuse-identity
  (let [{:keys [client calls race-release]} *context*
        before (count @calls)
        first-call (aor/agent-initiate client {:prompt "identity-race" :call-id "in-flight"})]
    (try
      (loop [remaining 200]
        (when (and (= before (count @calls)) (pos? remaining))
          (Thread/sleep 10) (recur (dec remaining))))
      (is (= (inc before) (count @calls)))
      (let [second-call (aor/agent-initiate client {:prompt "different" :call-id "in-flight"})
            result (try (.get (aor/agent-result-async client second-call) 1 TimeUnit/SECONDS)
                        (catch Exception _ :no-typed-conflict))]
        (is (= {:error {:type :bridge.replay/identity-conflict}} result)))
      (finally (deliver race-release true)))
    (aor/agent-result client first-call)
    (is (= 1 (- (count @calls) before)))))

(defn streamed-result [client request]
  (let [invoke (aor/agent-initiate client request)
        completed (promise)]
    (with-open [stream (aor/agent-stream client invoke "model"
                                         (fn [all _new _reset? done?]
                                           (when done? (deliver completed all))))]
      {:invoke invoke :result (aor/agent-result client invoke)
       :chunks (deref completed 10000 :timeout)
       :resets (aor/agent-stream-reset-info stream)})))

(deftest completed-tool-exchange-replays-serialized-tool-calls-and-chunks
  (let [{:keys [client calls]} *context*
        before (count @calls)
        request {:prompt "fixture" :tool? true :call-id "tool-replay"}
        original (streamed-result client request)
        replayed (streamed-result client request)]
    (is (= "ping" (get-in replayed [:result :text])))
    (is (= (select-keys original [:result :chunks]) (select-keys replayed [:result :chunks])))
    (is (= 2 (- (count @calls) before)))))

(deftest worker-replacement-retains-completed-replay-and-rebuilds-agent-objects
  (let [{:keys [client calls ipc module-name config]} *context*
        before (count @calls)
        request {:prompt "fixture" :call-id "worker-restart" :force-retry? true}
        original (streamed-result client request)]
    (rtest/update-module! ipc (module/proxy-module config))
    (let [replayed (streamed-result client request)]
      (is (= (select-keys original [:result :chunks]) (select-keys replayed [:result :chunks])))
      (is (= 1 (:resets replayed)))
      (is (= 2 (count (inspect/model-operations ipc module-name client (:invoke replayed)))))
      (is (= 1 (- (count @calls) before))))))

(deftest expired-generation-cannot-overwrite-a-new-completion
  (let [{:keys [client calls generation-release ipc module-name]} *context*
        before (count @calls)
        first-call (with-redefs [replay/binding-retention-ms 200]
                     (let [invoke (aor/agent-initiate client {:prompt "generation-first" :call-id "generation"})]
                       (loop [remaining 200]
                         (when (and (= before (count @calls)) (pos? remaining))
                           (Thread/sleep 10) (recur (dec remaining))))
                       invoke))]
    (try
      (is (= (inc before) (count @calls)))
      (let [state (rama/foreign-pstate ipc module-name "$$completed-calls")]
        (loop [remaining 100]
          (when (and (rama/foreign-select-one ["generation/0"] state) (pos? remaining))
            (Thread/sleep 20) (recur (dec remaining)))))
      (is (= "new" (:text (aor/agent-invoke client {:prompt "generation-new" :call-id "generation"}))))
      (finally (deliver generation-release true)))
    (is (= "one two" (:text (aor/agent-result client first-call))))
    (is (= "new" (:text (aor/agent-invoke client {:prompt "generation-new" :call-id "generation"}))))
    (is (= 2 (- (count @calls) before)))))

(deftest fingerprint-covers-request-fields-and-canonicalizes-object-order
  (let [base (replay/fingerprint (lc4j/chat-request ["fixture"]) {})]
    (doseq [options [{:temperature 0.1} {:top-p 0.2} {:top-k 2}
                     {:frequency-penalty 0.3} {:presence-penalty 0.4}
                     {:max-output-tokens 20} {:model-name "other"}
                     {:stop-sequences ["stop"]} {:tool-choice :required}
                     {:tools [module/echo-tool]}]]
      (is (not= base (replay/fingerprint (lc4j/chat-request ["fixture"] options) {}))))
    (is (not= base (replay/fingerprint (lc4j/chat-request ["other"]) {})))
    (is (not= base (replay/fingerprint (lc4j/chat-request ["fixture"]) {:model "other"}))))
  (let [request (fn [properties]
                  (lc4j/chat-request ["fixture"]
                                     {:response-format (lc4j/json-response-format
                                                        "result" (schema/object properties))}))
        a ["a" (schema/string)] b ["b" (schema/boolean)]]
    (is (= (replay/fingerprint (request (into (array-map) [a b])) {:a 1 :b 2})
           (replay/fingerprint (request (into (array-map) [b a])) {:b 2 :a 1}))))
  (is (= 3600000 replay/retention-ms)))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'bridge.ipc-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
