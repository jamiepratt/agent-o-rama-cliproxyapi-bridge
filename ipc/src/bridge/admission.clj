(ns bridge.admission
  "Module-wide in-flight generations in one atomic Rama partition."
  (:require [bridge.locks :as locks]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.store :as store]
            [com.rpl.rama :as rama])
  (:import [java.util UUID]
           [java.util.concurrent TimeUnit TimeoutException]
           [java.io Closeable]))

(def state-key "admission")
(def command-retention-ms 3600000)

(defn command-expired? [now deadline] (<= deadline now))
(defn new-command? [now deadline receipt]
  (and (nil? receipt) (not (command-expired? now deadline))))
(defn command-status [now deadline]
  (if (command-expired? now deadline) :expired :applied))

(defn open-state [node]
  {:pstate (store/get-underlying-pstate (aor/get-store node "$$admission"))
   :depot (aor/get-depot node "*admission-changes")})

(defn change! [state f]
  (let [command {:key state-key :id (str (UUID/randomUUID))
                 :expires-at (+ (System/currentTimeMillis) command-retention-ms) :transform f}
        result (rama/foreign-append! (:depot state) command)]
    (when-not (= [:applied] (vec (vals result)))
      (throw (ex-info "Admission command was not applied" {:type ::command-expired})))))

(defn await-operation [^java.util.concurrent.CompletableFuture operation stopped?]
  (loop []
    (when (and stopped? @stopped?)
      (.cancel operation false)
      (throw (ex-info "Worker model is stopping" {:type ::worker-stopping})))
    (let [result (try [(.get operation 100 TimeUnit/MILLISECONDS)]
                      (catch TimeoutException _ nil))]
      (if result (first result) (recur)))))

(defn snapshot
  ([state] (snapshot state nil))
  ([state stopped?]
   (await-operation (rama/foreign-select-one-async [state-key] (:pstate state)) stopped?)))

(defn entry [{:keys [state generation stopped?]}]
  (await-operation (rama/foreign-select-one-async [state-key :entries generation] (:pstate state)) stopped?))

(def cancellation-retention-ms 3600000)
(def waiter-lease-ms 180000)

(defn cancelled? [state call-id now]
  (or (< now (get-in state [:cancellations call-id] 0))
      (some #(and (= call-id (:call-id %)) (:cancelled? %) (not (:terminal? %)))
            (vals (:entries state)))))

(defn check-cancelled! [node call-id]
  (when (cancelled? (snapshot (open-state node)) call-id (System/currentTimeMillis))
    (throw (ex-info "Model call was cancelled" {:type ::cancelled}))))

(defn register [state identity call-id candidate {:keys [active-limit queue-limit]} now]
  (if (cancelled? state call-id now)
    (assoc-in state [:waiters candidate] :cancelled)
    (if-let [generation (get-in state [:identities identity])]
      (assoc-in state [:waiters candidate] generation)
      (let [active? (< (count (:active state)) active-limit)]
        (if (and (not active?) (>= (count (:queue state)) queue-limit))
          (assoc-in state [:waiters candidate] :overloaded)
          (-> state
              (assoc-in [:waiters candidate] candidate)
              (assoc-in [:identities identity] candidate)
              (assoc-in [:entries candidate] {:identity identity :call-id call-id :chunks [] :phase (if active? :active :queued)})
              (update (if active? :active :queue) (fnil conj (if active? #{} [])) candidate)))))))

(declare finish! recover!)

(defn ensure-domain! [state options]
  (let [current (snapshot state (:stopped? options))]
    (when (and (seq (:entries current)) (:domain current)
               (not= (:domain current) (:domain options)))
      (throw (ex-info "Pending calls require the original private lock directory" {:type ::invalid-configuration})))))

(defn release-fence! [{:keys [fence]}]
  (when fence
    (.close ^Closeable fence)))

(defn join! [node identity call-id options]
  (let [state (open-state node)
        candidate (str (UUID/randomUUID))
        directory (:directory options)
        fence (locks/try-acquire! directory candidate)]
    (try
      (ensure-domain! state options)
      (recover! state options)
      (let [now (System/currentTimeMillis)
            domain (:domain options)
            limits (select-keys options [:active-limit :queue-limit])]
        (change! state
                 (fn [current]
                   (let [current (assoc-in current [:waiter-expires candidate] (+ now waiter-lease-ms))]
                     (if (and (seq (:entries current))
                              (or (and (:domain current) (not= (:domain current) domain))
                                  (and (:limits current) (not= (:limits current) limits))))
                       (assoc-in current [:waiters candidate] :invalid-configuration)
                       (-> (register current identity call-id candidate limits now)
                           (assoc :domain domain :limits limits)))))))
      (let [generation (get-in (snapshot state (:stopped? options)) [:waiters candidate])]
        (when (keyword? generation)
          (release-fence! {:fence fence})
          (locks/remove-retired! directory candidate)
          (change! state #(-> % (update :waiters dissoc candidate) (update :waiter-expires dissoc candidate)))
          (throw (ex-info "Model call admission rejected" {:type (keyword "bridge.admission" (name generation))})))
        (let [owner? (= candidate generation)]
          (when-not owner?
            (release-fence! {:fence fence})
            (locks/remove-retired! directory candidate))
          (merge options {:state state :candidate candidate :generation generation
                          :heartbeat-at (atom (+ (System/currentTimeMillis) 15000))
                          :owner? owner? :fence (when owner? fence)})))
      (catch Throwable error
        (release-fence! {:fence fence :directory directory :generation candidate})
        (throw error)))))

(defn check-running! [{:keys [stopped?]}]
  (when @stopped?
    (throw (ex-info "Worker model is stopping" {:type ::worker-stopping}))))

(defn heartbeat! [{:keys [state candidate heartbeat-at]}]
  (let [now (System/currentTimeMillis) previous @heartbeat-at]
    (when (and (<= previous now) (compare-and-set! heartbeat-at previous (+ now 15000)))
      (change! state #(if (contains? (:waiters %) candidate)
                        (assoc-in % [:waiter-expires candidate] (+ now waiter-lease-ms)) %)))))

(defn claim-reservation! [{:keys [state candidate generation directory stopped?] :as call}]
  (let [current (entry call)]
    (when (and current (not (:terminal? current)) (not (:dispatched? current)))
      (when-let [fence (locks/try-acquire! directory generation)]
        (try
          (let [current (entry call)
                waiter (await-operation (rama/foreign-select-one-async [state-key :waiters candidate] (:pstate state)) stopped?)]
            (if (and (= waiter generation) current (not (:terminal? current)) (not (:dispatched? current)))
              (assoc call :owner? true :fence fence)
              (do (.close ^Closeable fence) nil)))
          (catch Throwable error (.close ^Closeable fence) (throw error)))))))

(defn mark-dispatched! [{:keys [state generation] :as call}]
  (change! state #(let [current (get-in % [:entries generation])]
                    (if (and current (not (:terminal? current)) (not (:cancelled? current)))
                      (assoc-in % [:entries generation :dispatched?] true) %)))
  (let [current (entry call)]
    (when-not current (throw (ex-info "Admission generation expired" {:type ::generation-expired})))
    (and (:dispatched? current) (not (:terminal? current)) (not (:cancelled? current)))))

(defn await-turn! [{:keys [state] :as call}]
  (try
    (loop []
      (check-running! call)
      (heartbeat! call)
      (recover! state call)
      (let [current (entry call)]
        (when-not current
          (throw (ex-info "Admission generation expired" {:type ::generation-expired})))
        (when (and (= :queued (:phase current)) (not (:terminal? current)))
          (Thread/sleep 25)
          (recur))))
    (catch Throwable error
      ;; No local transport was started. A stale worker may no longer write state;
      ;; releasing its fence lets the next worker recover the durable generation.
      (release-fence! call)
      (throw error))))

(defn chunk! [{:keys [state generation]} chunk]
  (change! state #(if (or (nil? (get-in % [:entries generation]))
                          (get-in % [:entries generation :cancelled?])
                          (get-in % [:entries generation :terminal?]))
                    % (update-in % [:entries generation :chunks] conj chunk))))

(defn discard-unused [current generation]
  (if (and (get-in current [:entries generation :terminal?])
           (not-any? #{generation} (vals (:waiters current))))
    (update current :entries dissoc generation)
    current))

(defn complete [current generation terminal]
  (let [entry (get-in current [:entries generation])]
    (if (or (nil? entry) (:terminal? entry))
      current
      (let [was-active? (contains? (:active current) generation)
            next (-> current
                     (update :identities dissoc (:identity entry))
                     (update :active disj generation)
                     (update :queue #(vec (remove #{generation} %)))
                     (update-in [:entries generation] merge terminal))]
        (if-let [promoted (when was-active? (first (:queue next)))]
          (-> next
              (update :queue #(vec (rest %)))
              (update :active conj promoted)
              (assoc-in [:entries promoted :phase] :active))
          next)))))

(defn finish! [{:keys [state generation directory] :as call} terminal]
  (try
    (let [now (System/currentTimeMillis)]
      (change! state
               (fn [current]
                 (let [call-id (get-in current [:entries generation :call-id])
                       next (if (cancelled? current call-id now)
                              (assoc-in current [:cancellations call-id] (+ now cancellation-retention-ms)) current)]
                   (discard-unused (complete next generation (assoc terminal :expires-at (+ now cancellation-retention-ms))) generation)))))
    (finally (release-fence! call)))
  ;; A failed write or queued handoff is not retirement: its inode must survive.
  (locks/remove-retired! directory generation))

(defn recover! [state {:keys [directory recovery-at stopped?] :as options}]
  (let [now (System/currentTimeMillis)
        previous @recovery-at]
    (when (and (<= previous now) (compare-and-set! recovery-at previous (+ now 250)))
      (let [current (snapshot state stopped?)]
        (when (and (seq (:entries current)) (not= (:domain current) (:domain options)))
          (throw (ex-info "Recovery requires the original lock directory" {:type ::invalid-configuration})))
        (doseq [generation (concat (:active current) (:queue current))]
          (when-let [fence (locks/try-acquire! directory generation)]
            (with-open [_ fence]
              (let [latest (snapshot state stopped?)
                    entry (get-in latest [:entries generation])
                    live-waiter? (some (fn [[candidate owner]]
                                         (and (= generation owner) (< now (get-in latest [:waiter-expires candidate] 0))))
                                       (:waiters latest))]
              ;; A free fence is mandatory. Time never retires a live transport.
                (if (and entry (or (:dispatched? entry) (not live-waiter?)))
                  (finish! {:state state :generation generation :directory directory :fence fence}
                           {:terminal? true :error "bridge.admission.WorkerLost"})
                  (.close ^Closeable fence))))))))))

(defn cancel! [node call-id]
  (let [state (open-state node)
        now (System/currentTimeMillis)]
    (change! state
             (fn [current]
               (reduce (fn [next [generation entry]]
                         (if (and (= call-id (:call-id entry)) (not (:terminal? entry)))
                           (if (= :queued (:phase entry))
                             (complete next generation {:terminal? true :cancelled? true :expires-at (+ now cancellation-retention-ms)})
                             (assoc-in next [:entries generation :cancelled?] true))
                           next))
                       (assoc-in current [:cancellations call-id] (+ now cancellation-retention-ms))
                       (:entries current))))
    {:cancelled true}))

(defn leave! [{:keys [state candidate generation]}]
  (change! state #(discard-unused (-> % (update :waiters dissoc candidate) (update :waiter-expires dissoc candidate)) generation)))

(defn prune [now current]
  (let [expired (set (for [[generation entry] (:entries current)
                           :when (and (:terminal? entry) (<= (:expires-at entry) now))]
                       generation))
        abandoned (set (for [[candidate generation] (:waiters current)
                             :when (or (contains? expired generation)
                                       (<= (get-in current [:waiter-expires candidate] 0) now))]
                         candidate))]
    (-> current
        (update :entries #(apply dissoc % expired))
        (update :waiters #(apply dissoc % abandoned))
        (update :waiter-expires #(apply dissoc % abandoned))
        (update :cancellations #(into {} (remove (fn [[_ deadline]] (<= deadline now))) %)))))

(defn await! [{:keys [state] :as call} on-chunk on-owner]
  (try
    (loop [seen 0]
      (check-running! call)
      (heartbeat! call)
      (recover! state call)
      (when-let [owned (claim-reservation! call)] (on-owner owned))
      (let [{:keys [chunks terminal? cancelled?] :as entry} (entry call)]
        (when-not entry
          (throw (ex-info "Admission generation expired" {:type ::generation-expired})))
        (doseq [chunk (drop seen chunks)] (on-chunk chunk))
        (if (or terminal? cancelled?)
          entry
          (do (Thread/sleep 25) (recur (count chunks))))))
    (finally (leave! call))))
