(ns bridge.module
  "Compatibility spike only; no replay, admission or provider retry policy."
  (:require [clojure.string :as str]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.langchain4j :as lc4j]
            [com.rpl.agent-o-rama.langchain4j.json :as schema]
            [com.rpl.agent-o-rama.tools :as tools])
  (:import [dev.langchain4j.model.openaiofficial OpenAiOfficialStreamingChatModel]
           [java.time Duration]
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

(defn model-roundtrip [node prompt tool? timeout? model-name]
  (let [model (aor/get-agent-object node (if timeout? "deadline-model" "model"))
        response (lc4j/chat model (lc4j/chat-request [prompt]
                                                     (cond-> {}
                                                       tool? (assoc :tools [echo-tool] :tool-choice :required)
                                                       model-name (assoc :model-name model-name))))
        message (.aiMessage response)
        calls (vec (.toolExecutionRequests message))]
    (if (seq calls)
      (let [results (aor/agent-invoke (aor/agent-client node "tools") calls)]
        (lc4j/chat model (lc4j/chat-request (into [prompt message] results))))
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

(defn proxy-module [{:keys [base-url model timeout-ms bearer-file deadline-ms]
                     :or {model "fixture" timeout-ms 30000 deadline-ms 1}}]
  (aor/agentmodule
   {:module-name "ProxyModule"} [topology]
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
            .build))))
    ;; Intentionally one forced failure per launched IPC module.
   (aor/declare-agent-object-builder topology "retry-latch" (fn [_] (atom false)))
   (tools/new-tools-agent topology "tools" [echo-tool])
   (-> (aor/new-agent topology "chat")
       (aor/node
        "model" nil
        (fn [node {:keys [prompt force-retry? tool? timeout? model-name]}]
          (let [response (model-roundtrip node prompt tool? timeout? model-name)]
            (when (and force-retry?
                       (compare-and-set! (aor/get-agent-object node "retry-latch") false true))
              (throw (ex-info "Forced post-model Rama retry" {:fixture true})))
            (aor/result! node (response-data response))))))))
