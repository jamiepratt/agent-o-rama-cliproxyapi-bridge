(ns bridge.ipc-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [clojure.data.json :as json]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.rama :as rama]
            [com.rpl.rama.test :as rtest]
            [bridge.module :as module]
            [bridge.inspect :as inspect])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

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
               deltas (cond tool-result? [{:content "ping"}]
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
                     (when (and (= prompt "pause") (zero? i)) (deref release 10000 nil)))
                   (send-event! out (assoc chunk :choices [{:index 0 :delta {} :finish_reason (if tool? "tool_calls" "stop")}]))
                   (send-event! out (assoc chunk :choices [] :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}))
                   (send-event! out "[DONE]"))))
             (catch java.io.IOException _ nil))))))
    (.start server)
    server))

(def ^:dynamic *context* nil)

(defn with-ipc [test-fn]
  (let [calls (atom []) release (promise) server (fake-server calls release)]
    (try
      (with-open [ipc (rtest/create-ipc)]
        (let [mod (module/proxy-module {:deadline-ms 100 :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")})]
          (rtest/launch-module! ipc mod {:tasks 1 :threads 1})
          (binding [*context* {:ipc ipc :module-name (rama/get-module-name mod) :calls calls :release release
                               :client (aor/agent-client (aor/agent-manager ipc (rama/get-module-name mod)) "chat")}]
            (test-fn))))
      (finally (.stop server 0)))))

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

(deftest rama-retry-repeats-upstream-call
  (let [{:keys [client calls]} *context* before (count @calls)]
    (is (= "one two" (:text (aor/agent-invoke client {:prompt "fixture" :force-retry? true}))))
    (is (= 2 (- (count @calls) before)))))

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

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'bridge.ipc-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
