(ns bridge.module
  "Private IPC integration of completed replay, streaming and Agent-o-rama tracing."
  (:require [clojure.string :as str]
            [bridge.replay :as replay]
            [bridge.admission :as admission]
            [com.rpl.rama :refer [module declare-depot hash-by declare-tick-depot <<sources source> |all local-transform> local-select> <<if ack-return> NONE>]]
            [com.rpl.rama.path :refer [MAP-VALS pred term termval must]]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.langchain4j :as lc4j]
            [com.rpl.agent-o-rama.langchain4j.json :as schema]
            [com.rpl.agent-o-rama.tools :as tools])
  (:import [dev.langchain4j.model.openaiofficial OpenAiOfficialStreamingChatModel]
           [java.time Duration]
           [java.util UUID]
           [java.nio.file Files Paths]
           [java.nio.file.attribute PosixFilePermission]))

(defn response-data [response]
  (let [usage (.tokenUsage response)]
    {:text (.text (.aiMessage response))
     :usage {:input (.inputTokenCount usage)
             :output (.outputTokenCount usage)
             :total (.totalTokenCount usage)}}))

(def echo-tool
  (tools/tool-info
   (tools/tool-specification "spike_echo"
                             (schema/object {:required ["value"] :additional-properties? false}
                                            {"value" (schema/string)}))
   (fn [args] (get args "value"))))

(defn model-roundtrip [node prompt tool? timeout? model-name identity observation-id]
  (let [model (aor/get-agent-object node (if timeout? "deadline-model" "model"))
        response (binding [replay/*call* {:node node :call-id identity :identity (str identity "/0") :observation-id (when observation-id (str observation-id "/0"))}]
                   (lc4j/chat model
                              (lc4j/chat-request [prompt]
                                                 (cond-> {}
                                                   tool? (assoc :tools [echo-tool] :tool-choice :required)
                                                   model-name (assoc :model-name model-name)))))
        message (.aiMessage response)
        calls (vec (.toolExecutionRequests message))]
    (if (seq calls)
      (let [_ (admission/check-cancelled! node identity)
            results (aor/agent-invoke (aor/agent-client node "tools") calls)]
        (binding [replay/*call* {:node node :call-id identity :identity (str identity "/1") :observation-id (when observation-id (str observation-id "/1"))}]
          (lc4j/chat model (lc4j/chat-request (into [prompt message] results)))))
      response)))

(defn read-bearer [bearer-file]
  (if bearer-file
    (let [path (Paths/get bearer-file (make-array String 0))
          permissions (Files/getPosixFilePermissions path (make-array java.nio.file.LinkOption 0))
          forbidden #{PosixFilePermission/GROUP_READ PosixFilePermission/GROUP_WRITE
                      PosixFilePermission/GROUP_EXECUTE PosixFilePermission/OTHERS_READ
                      PosixFilePermission/OTHERS_WRITE PosixFilePermission/OTHERS_EXECUTE}]
      (when (some forbidden permissions)
        (throw (ex-info "Bearer file must be private" {})))
      (str/trim (slurp bearer-file)))
    "ipc-fixture"))

(defn valid-request? [request]
  (and (map? request)
       (every? #{:prompt :call-id :tool? :timeout? :model-name :force-retry?} (keys request))
       (string? (:prompt request))
       (every? (fn [k] (or (not (contains? request k)) (boolean? (get request k))))
               [:tool? :timeout? :force-retry?])
       (every? (fn [k] (or (not (contains? request k))
                           (and (string? (get request k)) (not (str/blank? (get request k))))))
               [:call-id :model-name])))

;; Rama embeds field-position function values as bytecode constants. Construct
;; these paths in Clojure so worker classloaders resolve functions through Vars.
(defn complete-call-path [identity completion]
  [identity (term (partial replay/complete-command completion))])

(defn expired-calls-path [now]
  [MAP-VALS (pred (partial replay/expired? now)) NONE>])

(defn expired-receipts-path [now]
  [MAP-VALS (pred (partial admission/command-expired? now)) NONE>])

(defn prune-admission-path [now]
  [(must admission/state-key) (term (partial admission/prune now))])

(defn proxy-module [{:keys [base-url model timeout-ms bearer-file deadline-ms sweep-ms active-limit queue-limit lock-dir]
                     :or {model "fixture" timeout-ms 30000 deadline-ms 1 sweep-ms 60000 active-limit 10 queue-limit 50
                          lock-dir (str (System/getProperty "user.home") "/.local/share/agent-o-rama-bridge/locks")}}]
  (when-not (and (integer? active-limit) (pos? active-limit)
                 (integer? queue-limit) (<= 0 queue-limit))
    (throw (ex-info "Admission limits must be positive active and nonnegative queued integers" {:type :bridge.admission/invalid-configuration})))
  (module
   {:module-name "ProxyModule"} [setup topologies]
   (let [topology (aor/agent-topology setup topologies)]
     (declare-tick-depot setup *replay-tick sweep-ms)
     (declare-depot setup *admission-changes (hash-by :key))
     (declare-depot setup *completed-changes (hash-by :identity))
     (aor/declare-pstate-store topology "$$completed-calls" {String Object})
     (aor/declare-pstate-store topology "$$admission" {String Object})
     (aor/declare-pstate-store topology "$$admission-receipts" {String Long})
     (doseq [[object-name duration] [["model" timeout-ms] ["deadline-model" deadline-ms]]]
       (aor/declare-agent-object-builder
        topology object-name
        (fn [_]
          (-> (OpenAiOfficialStreamingChatModel/builder)
              (.baseUrl base-url)
             ;; The harness injects the bearer at its forwarding boundary.
              (.apiKey (read-bearer bearer-file))
              (.modelName model)
              (.maxRetries (int 0))
              (.timeout (Duration/ofMillis duration))
              .build
              (replay/replay-model {:base-url base-url :model model :timeout-ms duration}
                                   {:active-limit active-limit :queue-limit queue-limit :lock-dir lock-dir})))
        {:thread-safe? true}))
    ;; Intentionally one forced failure per retry-latch object.
     (aor/declare-agent-object-builder topology "retry-latch" (fn [_] (atom false)))
     (tools/new-tools-agent topology "tools" [echo-tool])
     (-> (aor/new-agent topology "cancel")
         (aor/node "cancel" nil
                   (fn [node request]
                     (aor/result! node
                                  (if (and (map? request) (= #{:call-id} (set (keys request)))
                                           (string? (:call-id request)) (not (str/blank? (:call-id request))))
                                    (admission/cancel! node (:call-id request))
                                    {:error {:type :bridge.replay/invalid-request}})))))
     (-> (aor/new-agent topology "chat")
         (aor/node "identify" "model"
                   (fn [node request]
                     (if (valid-request? request)
                       (aor/emit! node "model" (assoc request :call-id (or (:call-id request) (str (UUID/randomUUID))) :observation-id (str (UUID/randomUUID))))
                       (aor/result! node {:error {:type :bridge.replay/invalid-request}}))))
         (aor/node
          "model" nil
          (fn [node {:keys [prompt force-retry? tool? timeout? model-name call-id observation-id]}]
            (try
              (let [response (model-roundtrip node prompt tool? timeout? model-name call-id observation-id)]
                (when (and force-retry?
                           (compare-and-set! (aor/get-agent-object node "retry-latch") false true))
                  (throw (ex-info "Forced post-model Rama retry" {:fixture true})))
                (aor/result! node (response-data response)))
              (catch clojure.lang.ExceptionInfo error
                (if (#{:bridge.replay/identity-conflict :bridge.admission/overloaded :bridge.admission/cancelled} (:type (ex-data error)))
                  (aor/result! node {:error {:type (:type (ex-data error))}})
                  (throw error)))))))
     (let [stream (aor/underlying-stream-topology topology)]
       (<<sources stream
                  (source> *admission-changes :> *change)
                  (get *change :id :> *command-id)
                  (get *change :expires-at :> *expires)
                  (System/currentTimeMillis :> *command-now)
                  (local-select> [*command-id] $$admission-receipts :> *receipt)
                  (<<if (admission/new-command? *command-now *expires *receipt)
                        (get *change :transform :> *transform)
                        ;; No yield/repartition: receipt and mutation commit in one event.
                        (local-transform> [admission/state-key (term *transform)] $$admission)
                        (local-transform> [*command-id (termval *expires)] $$admission-receipts))
                  (admission/command-status *command-now *expires :> *status)
                  (ack-return> *status)
                  (source> *completed-changes :> *completion)
                  (get *completion :identity :> *identity)
                  (<<if (replay/completion-current? *completion)
                        (complete-call-path *identity *completion :> *complete-path)
                        (local-transform> *complete-path $$completed-calls))
                  (source> *replay-tick)
                  (|all)
                  (System/currentTimeMillis :> *now)
                  (expired-calls-path *now :> *expired-calls)
                  (local-transform> *expired-calls $$completed-calls)
                  (expired-receipts-path *now :> *expired-receipts)
                  (local-transform> *expired-receipts $$admission-receipts)
                  (prune-admission-path *now :> *prune-admission)
                  (local-transform> *prune-admission $$admission)))
     (aor/define-agents! topology))))
