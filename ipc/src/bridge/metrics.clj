(ns bridge.metrics
  "Fixed-cardinality aggregates stored inside the module's atomic controller.")

(def buckets [0.01 0.05 0.1 0.5 1.0 5.0 30.0 120.0])
(def counters {:requests "requests" :replays "replay_hits" :conflicts "conflicts"
               :coalesces "coalescing" :overloads "overloads" :retries "observed_retries"
               :upstream-errors "upstream_errors" :timeouts "upstream_timeouts" :dispatches "upstream_dispatches"})

(defn timeout? [error]
  (boolean (some #(re-find #"(?i)timeout" (.getName (class %)))
                 (take 16 (take-while some? (iterate #(.getCause ^Throwable %) error))))))

(defn increment [state counter]
  (update-in state [:metrics counter] (fnil inc 0)))

(defn begin [state observation now]
  (let [retry? (and observation (< now (get-in state [:observations observation] 0)))]
    (cond-> (increment state :requests)
      observation (assoc-in [:observations observation] (+ now 3600000))
      retry? (increment :retries))))

(defn elapsed [state seconds]
  (-> state
      (update-in [:metrics :latency-count] (fnil inc 0))
      (update-in [:metrics :latency-sum] (fnil + 0.0) seconds)
      (update-in [:metrics :buckets]
                 (fn [counts]
                   (reduce (fn [next bound] (if (<= seconds bound) (update next bound (fnil inc 0)) next)) counts buckets)))))

(defn render [state]
  (let [metrics (:metrics state)]
    (str
     (apply str (for [[key suffix] (sort-by val counters)]
                  (str "# TYPE bridge_" suffix "_total counter\nbridge_" suffix "_total " (get metrics key 0) "\n")))
     "# TYPE bridge_request_duration_seconds histogram\n"
     (apply str (for [bound buckets]
                  (str "bridge_request_duration_seconds_bucket{le=\"" bound "\"} " (get-in metrics [:buckets bound] 0) "\n")))
     "bridge_request_duration_seconds_bucket{le=\"+Inf\"} " (get metrics :latency-count 0) "\n"
     "bridge_request_duration_seconds_count " (get metrics :latency-count 0) "\n"
     "bridge_request_duration_seconds_sum " (get metrics :latency-sum 0.0) "\n")))
