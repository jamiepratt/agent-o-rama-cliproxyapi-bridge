(ns bridge.commands
  "Standalone versioned data commands. Not connected to the deployed topology."
  (:require [bridge.admission :as admission]
            [bridge.replay :as replay]))

(defn- identifier? [value]
  (and (string? value) (not (empty? value))))

(defn- timestamp? [value]
  (and (integer? value) (<= 0 value (- Long/MAX_VALUE 3600000))))

(def ^:private schemas
  {:admission/chunk {:generation identifier? :chunk string?}
   :admission/leave {:generation identifier? :candidate identifier?}
   :admission/cancel {:call-id identifier? :now timestamp?}
   :replay/reserve {:fingerprint identifier? :generation identifier? :now timestamp?}})

(defn- malformed! []
  (throw (ex-info "Malformed command" {:type ::invalid-command})))

(defn- validate! [value]
  ;; Check before destructuring: malformed sequential inputs are not maps.
  (when-not (and (map? value) (not (record? value))
                 (= #{:version :op :args} (set (keys value))))
    (malformed!))
  (let [{:keys [version op args]} value]
    (when-not (and (integer? version) (keyword? op))
      (malformed!))
    (when-not (= 1 version)
      (throw (ex-info "Unsupported command version" {:type ::unsupported-version})))
    (let [schema (get schemas op)]
      (when-not schema
        (throw (ex-info "Unsupported command operation" {:type ::unsupported-operation})))
      (when-not (and (map? args) (not (record? args))
                     (= (set (keys schema)) (set (keys args)))
                     (every? (fn [[key predicate]] (predicate (get args key))) schema))
        (malformed!)))
    ;; Container metadata and sorted-map comparators can themselves hold closures.
    {:version 1 :op op :args (into {} args)}))

(defn command
  "Create a validated version 1 data command. No executable values are accepted."
  [op args]
  (validate! {:version 1 :op op :args args}))

(defn apply-command
  "Validate and apply one command to admission state or a single replay entry.
  Does not submit, deduplicate or acknowledge a durable operation."
  [state value]
  (let [{:keys [op args]} (validate! value)
        {:keys [generation chunk candidate call-id now fingerprint]} args]
    (case op
      :admission/chunk
      (if (or (nil? (get-in state [:entries generation]))
              (get-in state [:entries generation :cancelled?])
              (get-in state [:entries generation :terminal?]))
        state
        (update-in state [:entries generation :chunks] conj chunk))
      :admission/leave
      (admission/discard-unused
       (-> state (update :waiters dissoc candidate) (update :waiter-expires dissoc candidate))
       generation)
      :admission/cancel
      (reduce (fn [current [entry-generation entry]]
                (if (and (= call-id (:call-id entry)) (not (:terminal? entry)))
                  (if (= :queued (:phase entry))
                    (admission/complete current entry-generation
                                        {:terminal? true :cancelled? true
                                         :expires-at (+ now admission/cancellation-retention-ms)})
                    (assoc-in current [:entries entry-generation :cancelled?] true))
                  current))
              (assoc-in state [:cancellations call-id] (+ now admission/cancellation-retention-ms))
              (:entries state))
      :replay/reserve
      (replay/reserve-entry state now fingerprint generation))))
