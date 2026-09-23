(ns bridge.live
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.rama :as rama]
            [com.rpl.rama.test :as rtest]
            [bridge.module :as module]
            [bridge.observability :as obs]
            [bridge.inspect :as inspect])
  (:import [java.util.concurrent TimeUnit]))

(def stage (atom "startup"))

(defn check! [condition label]
  (when-not condition (throw (ex-info "IPC acceptance check failed" {:stage label}))))

(defn result! [client invoke]
  (.get (aor/agent-result-async client invoke) 120 TimeUnit/SECONDS))

(defn counter [base]
  (get (json/read-str (slurp (str base "/count"))) "requests"))

(defn stream-probe [ipc module-name client request]
  (let [invoke (aor/agent-initiate client request) complete (promise)]
    (with-open [stream (aor/agent-stream client invoke "model"
                                         (fn [all _new _reset? done?] (when done? (deliver complete all))))]
      (let [result (result! client invoke)
            chunks (deref complete 10000 ::timeout)
            ops (inspect/model-operations ipc module-name client invoke)
            info (:info (last ops))
            usage (:usage result)]
        (check! (and (vector? chunks) (seq chunks)) "nested-chunks")
        (check! (= (:text result) (apply str chunks)) "nested-order")
        (check! (and (= #{:input :output :total} (set (keys usage)))
                     (every? #(and (number? %) (pos? %)) (vals usage))) "numeric-usage")
        (check! (pos? (count ops)) "model-trace")
        (when (:tool? request)
          (check! (= "ping" (str/trim (:text result))) "tool-result")
          (let [tool-requests (mapcat #(get (:info %) "toolRequests") ops)]
            (check! (and (= 1 (count tool-requests))
                         (= "spike_echo" (get (first tool-requests) "toolName"))
                         (= {"value" "ping"} (json/read-str (get (first tool-requests) "args"))))
                    "tool-arguments")))
        (check! (= [(:input usage) (:output usage) (:total usage)]
                   (mapv info ["inputTokenCount" "outputTokenCount" "totalTokenCount"])) "trace-usage")
        {:complete true :chunks (count chunks) :content_chars (count (:text result))
         :usage usage :trace_model_calls (count ops) :trace_usage_matches true
         :stream_resets (aor/agent-stream-reset-info stream)
         :tool_requests (reduce + (map #(count (get (:info %) "toolRequests")) ops))}))))

(defn failure-probe [ipc module-name client request]
  (let [invoke (aor/agent-initiate client request)
        failed? (try (result! client invoke) false (catch Exception _ true))
        ops (inspect/model-operations ipc module-name client invoke)
        recorded? (boolean (some #(seq (get (:info %) "failure")) ops))]
    (check! failed? "agent-error")
    (check! recorded? "error-trace")
    (check! (inspect/expected-failure? ops (if (:timeout? request) :timeout :http-400)) "failure-type")
    {:error_observed true :trace_failure true :expected_failure_type true}))

(defn cancellation-probe [client]
  (let [invoke (aor/agent-initiate client {:prompt "List the integers 1 through 250, separated by spaces."})
        first-chunk (promise) callbacks (atom 0)
        stream (aor/agent-stream client invoke "model"
                                 (fn [_all new _reset? _done?]
                                   (swap! callbacks inc)
                                   (when (seq new) (deliver first-chunk true))))]
    (try
      (check! (= true (deref first-chunk 90000 ::timeout)) "cancellation-first-chunk")
      (let [before-result? (not (aor/agent-invoke-complete? client invoke))]
        (.close stream)
        (let [before @callbacks result (result! client invoke)]
          (check! before-result? "subscription-closed-before-completion")
          (check! (seq (:text result)) "completion-after-unsubscribe")
          (check! (= before @callbacks) "callbacks-after-close")
          {:subscription_closed_before_completion true :agent_completed true
           :callbacks_after_close (- @callbacks before) :usage (:usage result)
           :upstream_termination_proven false :billing_cessation_proven false}))
      (finally (.close stream)))))

(defn observer-probe [ipc module-name base model scenarios]
  (reset! stage "observability")
  (let [state (rama/foreign-pstate ipc module-name "$$admission")
        observer (obs/start! {:snapshot #(rama/foreign-select-one ["admission"] state)
                              :configured? true :base-url (str base "/v1") :model model
                              :timeout-ms 15000})
        before (counter base)]
    (try
      (let [ready (obs/refresh! observer)
            text (slurp (str "http://127.0.0.1:" (:port observer) "/metrics"))
            metric (fn [name] (Long/parseLong (second (re-find (re-pattern (str "(?m)^bridge_" name "_total ([0-9]+)$")) text))))]
        (check! (and (:available ready) (:configured ready) (:verified ready) (integer? (:checked-at ready))) "readiness-verified")
        (let [forwarded (reduce + (map :proxy_requests (vals scenarios)))
              dispatches (metric "upstream_dispatches")
              errors (metric "upstream_errors")
              forwarded-errors (+ (get-in scenarios [:error :proxy_requests]) (get-in scenarios [:timeout :proxy_requests]))]
          (check! (and (<= forwarded dispatches)
                       (<= (- dispatches forwarded) (metric "upstream_timeouts"))
                       (= (- dispatches forwarded) (- errors forwarded-errors))
                       (= (inc dispatches) (metric "requests")) (= 1 (metric "replay_hits"))
                       (= (dec errors) (metric "observed_retries"))
                       (= 1 (- (counter base) before))
                       (str/includes? text "bridge_verification_status 1")) "metrics-observed"))
        {:available (:available ready) :configured (:configured ready) :verified (:verified ready)
         :timestamped (integer? (:checked-at ready)) :scrape_success true
         :requests (metric "requests") :dispatches (metric "upstream_dispatches")
         :replays (metric "replay_hits") :retries (metric "observed_retries")
         :upstream_errors (metric "upstream_errors") :probe_requests (- (counter base) before)})
      (finally (obs/stop! observer)))))

(defn run-probes []
  (let [base (System/getenv "IPC_BASE_URL") model (System/getenv "IPC_MODEL")]
    (with-open [ipc (rtest/create-ipc)]
      (let [mod (module/proxy-module {:base-url (str base "/v1") :model model :timeout-ms 90000})]
        (rtest/launch-module! ipc mod {:tasks 1 :threads 1})
        (let [module-name (rama/get-module-name mod)
              client (aor/agent-client (aor/agent-manager ipc module-name) "chat")
              measured (fn [label f]
                         (reset! stage label)
                         (let [before (counter base) result (f)]
                           (assoc result :proxy_requests (- (counter base) before))))
              normal (measured "stream" #(stream-probe ipc module-name client {:prompt "Reply with exactly OK."}))
              tool (measured "tool" #(stream-probe ipc module-name client
                                                   {:prompt "Call spike_echo with value ping, then reply with exactly the returned value." :tool? true}))
              retry (measured "retry" #(stream-probe ipc module-name client
                                                     {:prompt "Reply with exactly OK." :force-retry? true}))
              error (measured "error" #(failure-probe ipc module-name client
                                                      {:prompt "Reply OK." :model-name "spike-nonexistent-model"}))
              timeout (measured "timeout" #(failure-probe ipc module-name client
                                                          {:prompt "Reply OK." :timeout? true}))
              cancellation (measured "cancellation" #(cancellation-probe client))
              recovery (measured "recovery" #(stream-probe ipc module-name client {:prompt "Reply with exactly OK."}))]
          (check! (= 2 (:proxy_requests tool)) "tool-roundtrip-count")
          (check! (= 1 (:tool_requests tool)) "tool-request-count")
          (check! (= 1 (:proxy_requests retry)) "rama-retry-count")
          (check! (= 1 (:stream_resets retry)) "rama-stream-reset")
          {:agent_o_rama "0.10.0" :rama "1.9.0" :langchain4j "1.18.1-beta28"
           :observability (observer-probe ipc module-name base model
                                          {:stream normal :tool tool :retry retry :error error :timeout timeout
                                           :cancellation cancellation :recovery recovery})
           :stream normal :tool tool :retry retry :error error :timeout timeout
           :cancellation cancellation :recovery recovery})))))

(defn -main [& _]
  (try
    (println (json/write-str (run-probes)))
    (shutdown-agents)
    (System/exit 0)
    (catch Throwable e
      (println (json/write-str {:failed_stage (or (:stage (ex-data e)) @stage)}))
      (shutdown-agents)
      (System/exit 1))))
