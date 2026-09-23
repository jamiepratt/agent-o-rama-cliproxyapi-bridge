(ns bridge.module
  "Private IPC integration of completed replay, streaming and Agent-o-rama tracing."
  (:require [clojure.string :as str]
            [bridge.replay :as replay]
            [com.rpl.rama :refer [module declare-tick-depot <<sources source> |all local-transform> NONE>]]
            [com.rpl.rama.path :refer [MAP-VALS pred]]
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

(defn model-roundtrip [node prompt tool? timeout? model-name identity]
  (let [model (aor/get-agent-object node (if timeout? "deadline-model" "model"))
        response (binding [replay/*call* {:node node :identity (str identity "/0")}]
                   (lc4j/chat model
                              (lc4j/chat-request [prompt]
                                                 (cond-> {}
                                                   tool? (assoc :tools [echo-tool] :tool-choice :required)
                                                   model-name (assoc :model-name model-name)))))
        message (.aiMessage response)
        calls (vec (.toolExecutionRequests message))]
    (if (seq calls)
      (let [results (aor/agent-invoke (aor/agent-client node "tools") calls)]
        (binding [replay/*call* {:node node :identity (str identity "/1")}]
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

(defn proxy-module [{:keys [base-url model timeout-ms bearer-file deadline-ms sweep-ms]
                     :or {model "fixture" timeout-ms 30000 deadline-ms 1 sweep-ms 60000}}]
  (module
   {:module-name "ProxyModule"} [setup topologies]
   (let [topology (aor/agent-topology setup topologies)]
     (declare-tick-depot setup *replay-tick sweep-ms)
     (aor/declare-pstate-store topology "$$completed-calls" {String Object})
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
              (replay/replay-model {:base-url base-url :model model :timeout-ms duration})))))
    ;; Intentionally one forced failure per retry-latch object.
     (aor/declare-agent-object-builder topology "retry-latch" (fn [_] (atom false)))
     (tools/new-tools-agent topology "tools" [echo-tool])
     (-> (aor/new-agent topology "chat")
         (aor/node "identify" "model"
                   (fn [node request]
                     (if (valid-request? request)
                       (aor/emit! node "model" (assoc request :call-id (or (:call-id request) (str (UUID/randomUUID)))))
                       (aor/result! node {:error {:type :bridge.replay/invalid-request}}))))
         (aor/node
          "model" nil
          (fn [node {:keys [prompt force-retry? tool? timeout? model-name call-id]}]
            (try
              (let [response (model-roundtrip node prompt tool? timeout? model-name call-id)]
                (when (and force-retry?
                           (compare-and-set! (aor/get-agent-object node "retry-latch") false true))
                  (throw (ex-info "Forced post-model Rama retry" {:fixture true})))
                (aor/result! node (response-data response)))
              (catch clojure.lang.ExceptionInfo error
                (if (= :bridge.replay/identity-conflict (:type (ex-data error)))
                  (aor/result! node {:error {:type :bridge.replay/identity-conflict}})
                  (throw error)))))))
     (let [stream (aor/underlying-stream-topology topology)]
       (<<sources stream
                  (source> *replay-tick)
                  (|all)
                  (System/currentTimeMillis :> *now)
                  (local-transform> [MAP-VALS (pred (partial replay/expired? *now)) NONE>] $$completed-calls)))
     (aor/define-agents! topology))))
