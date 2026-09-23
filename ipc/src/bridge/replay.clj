(ns bridge.replay
  "Completed model-call replay in durable Rama state. Not in-flight deduplication."
  (:require [clojure.data.json :as json]
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
  [^StreamingChatModel upstream configuration]
  (reify StreamingChatModel
    (defaultRequestParameters [_] (.defaultRequestParameters upstream))
    (provider [_] (.provider upstream))
    (listeners [_] (.listeners upstream))
    (supportedCapabilities [_] (.supportedCapabilities upstream))
    (doChat [_ request handler]
      (let [{:keys [node identity]} *call*
            state (aor/get-store node "$$completed-calls")
            digest (fingerprint request configuration)
            entry (bind-call! state identity digest)
            generation (:generation entry)]
        (when (not= digest (:fingerprint entry))
          (throw (ex-info "Model-call identity conflicts with recorded request" {:type ::identity-conflict})))
        (if (:result entry)
          (do (doseq [chunk (:chunks entry)] (.onPartialResponse ^StreamingChatResponseHandler handler ^String chunk))
              (.onCompleteResponse ^StreamingChatResponseHandler handler (decode-response (:result entry))))
          (let [chunks (atom [])]
            (.doChat upstream request
                     (reify StreamingChatResponseHandler
                       (^void onPartialResponse [_ ^String chunk]
                         (swap! chunks conj chunk)
                         (.onPartialResponse ^StreamingChatResponseHandler handler chunk))
                       (onCompleteResponse [_ response]
                         (try
                           (let [result (encode-response response)
                                 completed-at (System/currentTimeMillis)
                                 saved-chunks @chunks]
                             (store/pstate-transform!
                              [(must identity) (term #(complete-entry % generation result saved-chunks completed-at))]
                              state identity)
                             (.onCompleteResponse ^StreamingChatResponseHandler handler response))
                           (catch Throwable e (.onError ^StreamingChatResponseHandler handler e))))
                       (onError [_ error] (.onError ^StreamingChatResponseHandler handler error))))))))))
