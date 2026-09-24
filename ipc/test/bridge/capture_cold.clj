;; Script entrypoint: perturb allocation before any application namespace loads.
(dotimes [_ (Long/parseLong (or (System/getenv "HASH_PERTURB") "0"))]
  (System/identityHashCode (Object.)))
(when (= "1" (System/getenv "BRIDGE_LOAD_MODULE_FIRST")) (require 'bridge.module))
(when (= "metrics" (second *command-line-args*)) (require 'bridge.ipc-test))
(require '[bridge.capture-test :as captures]
         '[bridge.replay :as replay]
         '[com.rpl.agent-o-rama.impl.store-impl :as store]
         '[com.rpl.ramaspecter :as path]
         '[taoensso.nippy :as nippy]
         '[com.rpl.nippy-serializable-fn])

(defn layout [f]
  {:class (.getName (class f))
   :fields (mapv (fn [field] [(.getName field) (.getName (.getType field))])
                 (remove #(java.lang.reflect.Modifier/isStatic (.getModifiers %))
                         (.getDeclaredFields (class f))))})

(defn path-layouts [value depth]
  (when (and (pos? depth)
             (re-find #"^(bridge\.|com\.rpl\.ramaspecter\.)" (.getName (class value))))
    (cons (layout value)
          (mapcat #(path-layouts % (dec depth)) (remove nil? (captures/captured-values value))))))

(defn inputs []
  (let [initial {:entries {"generation-A" {:chunks [] :identity ["identity-I" "digest-J"]
                                           :call-id "call-D" :phase :active}
                           "chunk-B" {:chunks ["untouched"] :identity ["unrelated"]
                                      :call-id "other-call" :phase :queued}}
                 :identities {["identity-I" "digest-J"] "generation-A" ["unrelated"] "chunk-B"}
                 :active #{"generation-A"} :queue ["chunk-B"]
                 :waiters {"waiter-C" "generation-A" "other" "generation-A"}
                 :waiter-expires {"waiter-C" 200 "other" 200}}]
    [initial (assoc-in initial [:entries "generation-A" :terminal?] true)
     (assoc-in initial [:entries "generation-A" :cancelled?] true)
     (update initial :entries dissoc "generation-A")]))

(let [[mode kind file] *command-line-args*]
  (case kind
    "admission"
    (let [transforms (captures/admission-transforms)]
      (println mode (mapv layout transforms))
      (doseq [f transforms]
        (assert (= 1 (count (captures/captured-values f)))))
      (if (= mode "write")
        (nippy/freeze-to-file file {:transforms transforms
                                    :expected (mapv #(mapv % (inputs)) transforms)})
        (let [{:keys [transforms expected]} (nippy/thaw-from-file file)]
          (assert (= expected (mapv #(mapv % (inputs)) transforms)))
          (println :admission-semantics-pass))))
    "reservation"
    (let [f (replay/reservation-transform {:now 100 :digest "digest-J" :candidate "candidate-K"})
          cases [nil {:fingerprint "original" :generation "unchanged" :expires-at 101}
                 {:fingerprint "expired" :generation "old" :expires-at 100}]]
      (println mode (layout f))
      (assert (= [{:now 100 :digest "digest-J" :candidate "candidate-K"}]
                 (captures/captured-values f)))
      (if (= mode "write")
        (nippy/freeze-to-file file f)
        (let [loaded (nippy/thaw-from-file file)]
          (assert (= (mapv f cases) (mapv loaded cases)))
          (assert (= {:fingerprint "digest-J" :generation "candidate-K" :expires-at 3600100}
                     (loaded nil)))
          (println :reservation-semantics-pass))))
    "metrics"
    (if (= mode "write")
      ((requiring-resolve 'bridge.ipc-test/-main))
      (let [transforms (nippy/thaw-from-file file)
            initial {:entries {"unrelated" {:chunks ["untouched"]}}}]
        (assert (= 5 (count transforms)))
        (doseq [[name f] transforms]
          (println name (layout f))
          (assert (= 1 (count (captures/captured-values f))))
          (let [params (first (captures/captured-values f))
                before (System/currentTimeMillis)
                result (f initial)
                after (System/currentTimeMillis)]
            (assert (= (:entries initial) (:entries result)))
            (cond
              (:metric params) (assert (= 1 (get-in result [:metrics (:metric params)])))
              (:seconds params) (do (assert (= 1 (get-in result [:metrics :latency-count])))
                                    (assert (= (:seconds params) (get-in result [:metrics :latency-sum]))))
              (contains? params :observation-id)
              (do (assert (= 1 (get-in result [:metrics :requests])))
                  (when-let [id (:observation-id params)]
                    (assert (<= (+ before 3600000) (get-in result [:observations id]) (+ after 3600000)))
                    (assert (= 1 (get-in (f result) [:metrics :retries]))))))))
        (println :metrics-semantics-pass)))
    "path"
    (if (= mode "write")
      (replay/bind-call!
       (reify store/PStateStoreInternal
         (pstate-transform* [_ _ p]
           (println :writer-path (vec (path-layouts p 8)))
           (nippy/freeze-to-file file p))
         (pstate-select-one* [_ _] {:expires-at Long/MAX_VALUE}))
       "identity-I" "digest-J")
      (let [p (nippy/thaw-from-file file)
            initial {"unrelated" {:fingerprint "untouched"}}
            result (path/multi-transform p initial)]
        (assert (= "digest-J" (get-in result ["identity-I" :fingerprint])))
        (assert (string? (get-in result ["identity-I" :generation])))
        (assert (= (initial "unrelated") (result "unrelated")))
        (assert (= result (path/multi-transform p result)))
        (println :generated-path-semantics-pass)))))
(shutdown-agents)
