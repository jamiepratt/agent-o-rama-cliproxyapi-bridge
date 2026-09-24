(ns probe
  "Private deployed-cluster streaming/replay check. Run with phase record call-id.
  Phases: warm writes a NEW private record; cold replays then invokes a new ID;
  replay only verifies the saved call. Finish within the one-hour replay TTL.
  Requires the production Rama distribution and exact deployed module on classpath."
  (:require [com.rpl.agent-o-rama :as aor]
            [clojure.data.json :as json]
            [clojure.edn :as edn])
  (:import [com.rpl.rama RamaClusterManager]
           [java.util.concurrent TimeUnit]
           [java.nio.file Files Paths LinkOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]))

(def stage (atom "startup"))
(defn check! [condition label]
  (when-not condition (throw (ex-info "Probe assertion failed" {:stage label}))))

(defn dispatches []
  (let [connection (.openConnection (.toURL (java.net.URI. "http://127.0.0.1:18318/metrics")))]
    (.setConnectTimeout connection 5000)
    (.setReadTimeout connection 15000)
    (with-open [in (.getInputStream connection)]
      (let [match (re-find #"(?m)^bridge_upstream_dispatches_total (\d+)$" (slurp in))]
        (check! match "dispatch-metric")
        (Long/parseLong (second match))))))

(defn stream! [client call-id]
  (let [pending (future (aor/agent-initiate client {:prompt "Reply with exactly OK." :call-id call-id}))
        invoke (try (.get pending 30000 TimeUnit/MILLISECONDS)
                    (finally (future-cancel pending)))
        complete (promise)]
    (with-open [_stream (aor/agent-stream client invoke "model"
                                          (fn [all _new _reset? done?]
                                            (when done? (deliver complete all))))]
      (let [result (.get (aor/agent-result-async client invoke) 120 TimeUnit/SECONDS)
            chunks (deref complete 10000 ::timeout)
            usage (:usage result)]
        (check! (and (vector? chunks) (seq chunks) (every? string? chunks)
                     (string? (:text result)) (seq (:text result))) "chunks")
        (check! (= (:text result) (apply str chunks)) "ordered-stream")
        (check! (and (= #{:input :output :total} (set (keys usage)))
                     (every? #(and (number? %) (pos? %)) (vals usage))) "usage")
        {:text (:text result) :chunks chunks :usage usage}))))

(defn summary [sample]
  {:chunks (count (:chunks sample)) :content_chars (count (:text sample)) :usage (:usage sample)})

(defn run-probe! [client phase record call-id]
  (check! (and (#{"warm" "cold" "replay"} phase) (seq call-id)) "arguments")
  (let [path (Paths/get record (make-array String 0))
        _ (check! (.isAbsolute path) "absolute-record")
        saved (if (= "warm" phase)
                (do (Files/createFile path (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                                                                       (PosixFilePermissions/fromString "rw-------"))]))
                    nil)
                (do (check! (and (not (Files/isSymbolicLink path))
                                 (= "rw-------" (PosixFilePermissions/toString
                                                 (Files/getPosixFilePermissions path (make-array LinkOption 0))))) "record-permissions")
                    (edn/read-string (slurp record))))]
    (when saved
      (check! (= call-id (:call-id saved)) "record-call-id")
      (check! (< (- (System/currentTimeMillis) (:created-ms saved)) 3600000) "record-replay-ttl"))
    (reset! stage "first-stream")
    (let [before (dispatches)
          first-result (stream! client call-id)
          middle (dispatches)]
      (check! (= (- middle before) (if (= "warm" phase) 1 0)) "first-dispatch")
      (when saved (check! (= (:sample saved) first-result) "saved-replay-equality"))
      (reset! stage "replay")
      (let [replay-result (stream! client call-id)
            after (dispatches)]
        (check! (= middle after) "replay-no-dispatch")
        (check! (= first-result replay-result) "replay-equality")
        (when (= "warm" phase)
          (spit record (pr-str {:call-id call-id :created-ms (System/currentTimeMillis) :sample first-result})))
        (let [fresh (when (= "cold" phase)
                      (reset! stage "new-stream")
                      (stream! client (str call-id "-new-" (java.util.UUID/randomUUID))))
              final-count (dispatches)]
          (check! (= (- final-count after) (if fresh 1 0)) "new-dispatch")
          {:ok true :phase phase :first (summary first-result)
           :new (when fresh (summary fresh))
           :new_dispatches (+ (- middle before) (- final-count after))
           :replay_dispatches (- after middle)})))))

(defn -main [& args]
  (try
    (check! (= 3 (count args)) "arguments")
    (reset! stage "connect")
    (with-open [cluster (RamaClusterManager/open {"conductor.host" "127.0.0.1"})]
      (let [client (aor/agent-client (aor/agent-manager cluster "bridge.module/ProxyModule") "chat")]
        (println (json/write-str (apply run-probe! client args)))))
    (shutdown-agents)
    (System/exit 0)
    (catch Throwable error
      (println (json/write-str {:ok false :stage @stage :error_class (.getName (class error))
                                :cause_classes (mapv #(-> % class .getName)
                                                     (take 8 (take-while some? (iterate #(.getCause ^Throwable %) error))))
                                :assertion (:stage (ex-data error))}))
      (shutdown-agents)
      (System/exit 1))))

(when (seq *command-line-args*) (apply -main *command-line-args*))
