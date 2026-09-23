(ns bridge.replay
  "Durable completed replay and shared in-flight model calls."
  (:require [clojure.data.json :as json]
            [bridge.admission :as admission]
            [bridge.locks :as locks]
            [clojure.walk :as walk]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.store :as store]
            [com.rpl.rama.path :refer [term must]])
  (:import [com.google.gson Gson GsonBuilder JsonDeserializer TypeAdapter TypeAdapterFactory]
           [dev.langchain4j.data.message AiMessage]
           [dev.langchain4j.model.output TokenUsage]
           [dev.langchain4j.model.openaiofficial OpenAiOfficialTokenUsage]
           [java.security MessageDigest]
           [java.util HexFormat UUID]
           [java.io Closeable]
           [dev.langchain4j.model.chat StreamingChatModel]
           [dev.langchain4j.model.chat.response ChatResponse StreamingChatResponseHandler]))

(def retention-ms 3600000)
(def binding-retention-ms 3600000)
(def ^:dynamic *call* nil)

(defn expired? [now entry] (<= (:expires-at entry) now))
(def ^Gson codec
  (-> (GsonBuilder.)
      (.registerTypeAdapter TokenUsage
                            (reify JsonDeserializer
                              (deserialize [_ element _type _context]
                                (.fromJson (Gson.) element OpenAiOfficialTokenUsage))))
      .create))

;; Schemas with identical fields can have different upstream meanings by type.
(def ^Gson fingerprint-codec
  (-> (GsonBuilder.)
      (.registerTypeAdapterFactory
       (reify TypeAdapterFactory
         (create [factory gson token]
           (let [raw (.getRawType token)]
             (when (and (.startsWith (.getName raw) "dev.langchain4j.")
                        (not (.isEnum raw)))
               (let [delegate (.getDelegateAdapter gson factory token)]
                 (proxy [TypeAdapter] []
                   (write [out value]
                     (if (nil? value)
                       (.nullValue out)
                       (do (.beginObject out)
                           (.name out "$class")
                           (.value out (.getName (class value)))
                           (.name out "$value")
                           (.write delegate out value)
                           (.endObject out))))
                   (read [reader] (.read delegate reader)))))))))
      .create))

(defn fingerprint [request configuration]
  (let [value [configuration (json/read-str (.toJson fingerprint-codec request))]
        canonical (walk/postwalk #(if (map? %) (into (sorted-map) %) %) value)]
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (json/write-str canonical) "UTF-8")))))

(defn encode-response [^ChatResponse response]
  {:message (.toJson codec (.aiMessage response))
   :metadata (.toJson codec (.metadata response))
   :metadata-class (.getName (class (.metadata response)))})

(defn decode-response [{:keys [message metadata metadata-class]}]
  (-> (ChatResponse/builder)
      (.aiMessage (.fromJson codec ^String message AiMessage))
      (.metadata (.fromJson codec ^String metadata (Class/forName metadata-class)))
      .build))

(defn reserve-entry [entry now digest generation]
  (if (and entry (not (expired? now entry)))
    entry
    {:fingerprint digest :generation generation :expires-at (+ now binding-retention-ms)}))

(defn complete-entry [entry generation result chunks completed-at]
  (if (and (= generation (:generation entry)) (not (:result entry)))
    (assoc entry :result result :chunks chunks :expires-at (+ completed-at retention-ms))
    entry))

(defn bind-call! [state identity digest]
  (loop []
    (let [now (System/currentTimeMillis)
          candidate (str (UUID/randomUUID))]
      (store/pstate-transform! [identity (term #(reserve-entry % now digest candidate))] state identity)
      (let [entry (store/pstate-select-one [identity] state)]
        (if (or (nil? entry) (expired? (System/currentTimeMillis) entry))
          (recur)
          entry)))))

(defn replay-model
  "Wrap the pinned official model; completion is durable before delivery to AOR."
  [^StreamingChatModel upstream configuration limits]
  (let [stopped? (atom false)
        options (merge limits (locks/prepare-directory! (:lock-dir limits))
                       {:stopped? stopped? :recovery-at (atom 0)})]
    (reify
      Closeable
      (close [_] (reset! stopped? true))
      StreamingChatModel
      (defaultRequestParameters [_] (.defaultRequestParameters upstream))
      (provider [_] (.provider upstream))
      (listeners [_] (.listeners upstream))
      (supportedCapabilities [_] (.supportedCapabilities upstream))
      (doChat [_ request handler]
        (let [{:keys [node identity call-id]} *call*
              _ (admission/check-cancelled! node call-id)
              state (aor/get-store node "$$completed-calls")
              digest (fingerprint request configuration)
              entry (bind-call! state identity digest)
              generation (:generation entry)]
          (when (not= digest (:fingerprint entry))
            (throw (ex-info "Model-call identity conflicts with recorded request" {:type ::identity-conflict})))
          (if (:result entry)
            (do (doseq [chunk (:chunks entry)] (.onPartialResponse ^StreamingChatResponseHandler handler ^String chunk))
                (.onCompleteResponse ^StreamingChatResponseHandler handler (decode-response (:result entry))))
            (let [call (admission/join! node [identity digest] call-id options)
                  original-error (atom nil)]
              (when (:owner? call)
                (admission/await-turn! call)
                (try
                  (if (let [current (admission/entry call)]
                        (when-not current (throw (ex-info "Admission generation expired" {:type :bridge.admission/generation-expired})))
                        (or (:cancelled? current) (:terminal? current)))
                    (admission/finish! call {:terminal? true :cancelled? true})
                    (if-let [saved-entry (let [latest (store/pstate-select-one [identity] state)] (when (and (:result latest) (= generation (:generation latest)) (= digest (:fingerprint latest))) latest))]
                      (let [saved (:result saved-entry)]
                        (doseq [chunk (:chunks saved-entry)] (admission/chunk! call chunk))
                        (admission/finish! call {:terminal? true :result saved}))
                      (.doChat upstream request
                               (reify StreamingChatResponseHandler
                                 (^void onPartialResponse [_ ^String chunk]
                                   (try (admission/chunk! call chunk)
                                        (catch Throwable error (compare-and-set! original-error nil error))))
                                 (onCompleteResponse [_ response]
                                   (try
                                     (when-let [error @original-error] (throw error))
                                     (let [result (encode-response response)
                                           completed-at (System/currentTimeMillis)
                                           current (admission/entry call)
                                           chunks (:chunks current)]
                                       (when-not (:cancelled? current)
                                         (store/pstate-transform!
                                          [(must identity) (term #(complete-entry % generation result chunks completed-at))]
                                          state identity))
                                       (admission/finish! call {:terminal? true :result result}))
                                     (catch Throwable error
                                       (reset! original-error error)
                                       (admission/finish! call {:terminal? true :error (.getName (class error))}))))
                                 (onError [_ error]
                                   (reset! original-error error)
                                   (admission/finish! call {:terminal? true :error (.getName (class error))}))))))
                  (catch Throwable error
                    (reset! original-error error)
                    (admission/finish! call {:terminal? true :error (.getName (class error))}))))
              (let [outcome (admission/await! call #(.onPartialResponse ^StreamingChatResponseHandler handler ^String %))]
                (if (:cancelled? outcome)
                  (.onError ^StreamingChatResponseHandler handler (ex-info "Model call was cancelled" {:type :bridge.admission/cancelled}))
                  (if-let [error (:error outcome)]
                    (.onError ^StreamingChatResponseHandler handler
                              (or @original-error (ex-info "Shared upstream call failed" {:type ::upstream-failure :cause-class error})))
                    (do
                      ;; A joined caller may hold a newer binding after expiry.
                      (store/pstate-transform!
                       [(must identity) (term #(complete-entry % generation (:result outcome) (:chunks outcome) (System/currentTimeMillis)))]
                       state identity)
                      (.onCompleteResponse ^StreamingChatResponseHandler handler (decode-response (:result outcome))))))))))))))
