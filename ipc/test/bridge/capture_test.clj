(ns bridge.capture-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [bridge.admission :as admission]
            [bridge.locks :as locks]
            [bridge.replay :as replay])
  (:import [java.lang.reflect Modifier]))

(defn captured-values [f]
  (mapv (fn [field] (.setAccessible field true) (.get field f))
        (remove #(Modifier/isStatic (.getModifiers %)) (.getDeclaredFields (class f)))))

(defn plain-data? [value]
  (and (nil? (meta value))
       (not (record? value))
       (not (instance? clojure.lang.Sorted value))
       (cond
         (#{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap} (class value))
         (every? plain-data? (mapcat identity value))
         (#{clojure.lang.PersistentVector clojure.lang.APersistentVector$SubVector
            clojure.lang.PersistentHashSet clojure.lang.PersistentList
            clojure.lang.PersistentList$EmptyList} (class value))
         (every? plain-data? value)
         :else (or (nil? value) (string? value) (keyword? value)
                   (number? value) (boolean? value)))))

(defn single-map? [f]
  (let [values (captured-values f)]
    (and (= 1 (count values)) (map? (first values)) (plain-data? (first values)))))

(defn assert-single-map! [f]
  (let [values (captured-values f)]
    (is (= 1 (count values)) (str (class f) " capture count"))
    (is (and (= 1 (count values)) (map? (first values)) (plain-data? (first values)))
        (str (class f) " must capture only plain data"))))

(deftest submitted-chunks-capture-one-data-map
  (let [submitted (atom nil)
        initial {:entries {"generation-A" {:chunks []}
                           "unrelated" {:chunks ["untouched"]}}}]
    (with-redefs [admission/change! (fn [_ f] (reset! submitted f))]
      (admission/chunk! {:state nil :generation "generation-A"} "chunk-B"))
    (assert-single-map! @submitted)
    (is (= (assoc-in initial [:entries "generation-A" :chunks] ["chunk-B"]) (@submitted initial)))
    (doseq [guard [:cancelled? :terminal?]]
      (let [state (assoc-in initial [:entries "generation-A" guard] true)]
        (is (= state (@submitted state)))))))

(defn admission-transforms []
  (let [captured (atom [])
        call {:state nil :generation "generation-A" :candidate "waiter-C"
              :heartbeat-at (atom 0) :directory "unused"}
        options {:directory "unused" :domain "domain-D" :active-limit 2 :queue-limit 3}]
    (with-redefs [admission/change! (fn [_ f] (swap! captured conj f))
                  admission/open-state (constantly nil)
                  admission/snapshot (constantly {})
                  admission/entry (constantly {:dispatched? true})
                  admission/recover! (fn [& _])
                  locks/try-acquire! (fn [& _])
                  locks/remove-retired! (fn [& _])]
      (admission/join! nil ["identity-I" "digest-J"] "call-D" options)
      (with-redefs [admission/snapshot (constantly {:waiters (reify clojure.lang.ILookup
                                                               (valAt [_ _] :overloaded)
                                                               (valAt [_ _ _] :overloaded))})]
        (try (admission/join! nil ["identity-I" "digest-J"] "call-D" options)
             (catch clojure.lang.ExceptionInfo _)))
      (admission/heartbeat! call)
      (admission/mark-dispatched! call)
      (admission/chunk! call "chunk-B")
      (admission/finish! call {:terminal? true :result {:message "result-E"}})
      (admission/cancel! nil "call-D")
      (admission/leave! call))
    ;; join! produces the registration transform on both admission outcomes.
    (vec (vals (into (array-map) (map (juxt class identity) @captured))))))

(deftest every-admission-producer-captures-one-map
  (let [transforms (admission-transforms)]
    (is (= 8 (count transforms)))
    (doseq [f transforms] (assert-single-map! f))
    (is (= #{#{:candidate :now :domain :limits :identity :call-id}
             #{:candidate} #{:candidate :now} #{:generation}
             #{:generation :chunk} #{:generation :terminal :now}
             #{:call-id :now} #{:candidate :generation}}
           (set (map #(set (keys (first (captured-values %)))) transforms))))
    (let [params (map #(first (captured-values %)) transforms)]
      (is (some #{{:generation "generation-A" :chunk "chunk-B"}} params))
      (is (some #{{:candidate "waiter-C" :generation "generation-A"}} params)))))

(deftest field-inspection-detects-an-extra-capture
  (let [a (str (random-uuid)) b (str (random-uuid))
        bad (fn [state] (assoc state :a a :b b))]
    (is (= 2 (count (captured-values bad))))
    (is (not (single-map? bad)))))

(deftest dispatch-preserves-state-and-guards
  (let [initial {:entries {"generation-A" {:chunks []}
                           "other" {:chunks ["untouched"]}}}
        submitted (atom nil)]
    (with-redefs [admission/change! (fn [_ f] (reset! submitted f))
                  admission/entry (constantly {:dispatched? true})]
      (admission/mark-dispatched! {:generation "generation-A"}))
    (let [result (@submitted initial)]
      (is (= {:chunks ["untouched"]} (get-in result [:entries "other"])))
      (is (true? (get-in result [:entries "generation-A" :dispatched?])))
      (is (= result (@submitted result))))
    (doseq [guard [:terminal? :cancelled?]]
      (let [state (assoc-in initial [:entries "generation-A" guard] true)]
        (is (= state (@submitted state)))))))

(deftest reservation-captures-distinct-string-roles
  (let [params {:now 100 :digest "digest-J" :candidate "candidate-K"}
        f (replay/reservation-transform params)
        reserved {:fingerprint "digest-J" :generation "candidate-K" :expires-at 3600100}]
    (assert-single-map! f)
    (is (= [params] (captured-values f)))
    (is (= reserved (f nil)))
    (is (= reserved (f reserved)))
    (is (= reserved (f {:fingerprint "old" :generation "old-generation" :expires-at 100})))
    (let [existing {:fingerprint "other" :generation "keep" :expires-at 101}]
      (is (= existing (f existing))))))

(deftest data-inspection-rejects-executable-containers
  (let [comparator (fn [a b] (compare a b))]
    (is (not (plain-data? (sorted-map-by comparator :a 1))))
    (is (not (plain-data? (lazy-seq [1]))))
    (is (not (plain-data? (with-meta {:a 1} {:hidden (fn [] 1)}))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'bridge.capture-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
