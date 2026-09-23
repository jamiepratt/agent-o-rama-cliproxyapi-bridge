(ns bridge.inspect
  "Version-pinned trace observation only. Agent-o-rama 0.10 has no public trace reader."
  (:require [com.rpl.agent-o-rama.impl.pobjects :as po]
            [com.rpl.agent-o-rama.impl.types :as types]
            [com.rpl.rama :as rama]
            [com.rpl.rama.path :refer [keypath]]))

(defn model-operations [ipc module-name client invoke]
  (let [{:keys [task-id agent-invoke-id]} invoke
        roots (rama/foreign-pstate ipc module-name (po/agent-root-task-global-name "chat"))
        root-id (rama/foreign-select-one [(keypath agent-invoke-id) :root-invoke-id]
                                         roots {:pkey task-id})
        query (:tracing-query (types/underlying-objects client))
        trace (rama/foreign-invoke-query query task-id [[task-id root-id]] 10000)]
    (->> (:invokes-map trace) vals (mapcat :nested-ops) (filter #(= :model-call (:type %))) vec)))

(defn expected-failure? [operations kind]
  (boolean
   (some (fn [operation]
           (let [failure (get (:info operation) "failure" "")]
             (case kind
               :http-400 (and (re-find #"com\.openai\.errors\.BadRequestException" failure)
                              (re-find #"400" failure))
               :timeout (re-find #"java\.net\.SocketTimeoutException|java\.io\.InterruptedIOException: timeout|java\.util\.concurrent\.TimeoutException" failure))))
         operations)))
