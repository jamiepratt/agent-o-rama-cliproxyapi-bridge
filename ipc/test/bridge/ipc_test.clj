(ns bridge.ipc-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.agent-o-rama.langchain4j :as lc4j]
            [com.rpl.agent-o-rama.langchain4j.json :as schema]
            [com.rpl.rama :as rama]
            [com.rpl.rama.path :as path]
            [bridge.replay :as replay]
            [bridge.observability :as obs]
            [bridge.observability-test :as obs-test]
            [bridge.admission :as admission]
            [bridge.capture-test :as capture-test]
            [taoensso.nippy :as nippy]
            [bridge.locks-test]
            [com.rpl.rama.test :as rtest]
            [bridge.module :as module]
            [bridge.inspect :as inspect])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit Executors ExecutionException TimeoutException]))

(defn send-event! [out value]
  (.write out (.getBytes (str "data: " (if (string? value) value (json/write-str value)) "\n\n") "UTF-8"))
  (.flush out))

(defn fake-server [calls release activity]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/v1/chat/completions"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
               prompt (get-in request [:messages 0 :content])
               measured? (str/starts-with? prompt "restart-")
               running? (atom measured?)
               finished! (fn [] (when (compare-and-set! running? true false) (swap! activity update :active dec)))
               tool-result? (some #(= "tool" (:role %)) (:messages request))
               tool? (seq (:tools request))
               chunk {:id "fixture" :object "chat.completion.chunk" :created 1 :model "fixture"}
               deltas (cond (= prompt "generation-new") [{:content "new"}]
                            tool-result? [{:content "ping"}]
                            tool? [{:tool_calls [{:index 0 :id "call_fixture" :type "function"
                                                  :function {:name "spike_echo" :arguments "{\"value\":\"ping\"}"}}]}]
                            :else [{:content "one"} {:content " two"}])]
           (when measured? (swap! activity (fn [s] (let [n (inc (:active s))] (assoc s :active n :maximum (max n (:maximum s)))))))
           (swap! calls conj request)
           (when (= "stall" prompt) (Thread/sleep 1000))
           (when (#{"failure-shared" "deadline-shared"} prompt)
             (deref (get release prompt) 120000 nil))
           (try
             (if (= "spike-nonexistent-model" (:model request))
               (let [body (.getBytes (json/write-str {:error {:message "fixture error" :type "invalid_request_error"}}) "UTF-8")]
                 (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                 (.sendResponseHeaders exchange 400 (count body))
                 (with-open [out (.getResponseBody exchange)] (.write out body)))
               (do
                 (.set (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
                 (.sendResponseHeaders exchange 200 0)
                 (with-open [out (.getResponseBody exchange)]
                   (doseq [[i delta] (map-indexed vector deltas)]
                     (send-event! out (assoc chunk :choices [{:index 0 :delta delta :finish_reason nil}]))
                     (when (and (contains? release prompt) (zero? i)) (deref (get release prompt) 120000 nil)))
                   (finished!)
                   (send-event! out (assoc chunk :choices [{:index 0 :delta {} :finish_reason (if tool? "tool_calls" "stop")}]))
                   (send-event! out (assoc chunk :choices [] :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}))
                   (send-event! out "[DONE]"))))
             (catch java.io.IOException _ nil)
             (finally (finished!)))))))
    (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
    (.start server)
    server))

(def ^:dynamic *context* nil)

(defn with-ipc
  ([test-fn] (with-ipc {} test-fn))
  ([options test-fn]
   (let [calls (atom [])
         release (promise) race-release (promise) generation-release (promise) coalesce-release (promise)
         activity (atom {:active 0 :maximum 0})
         bounded-release (into {"failure-shared" (promise) "deadline-shared" (promise) "expiry-coalesce" (promise) "expiry-cancel" (promise) "completion-expiry" (promise) "capture-race" (promise)}
                               (for [prefix ["bounded-" "cancel-" "restart-" "small-"] n (range 61)]
                                 [(str prefix n) (promise)]))
         gates (merge bounded-release {"pause" release "identity-race" race-release
                                       "generation-first" generation-release "coalesce" coalesce-release})
         server (fake-server calls gates activity)]
     (try
       (with-open [ipc (rtest/create-ipc)]
         (try
           (let [config (merge {:lock-dir (str (Files/createTempDirectory "bridge-admission-test-" (make-array FileAttribute 0)))
                                :sweep-ms 50 :timeout-ms 120000 :deadline-ms 100
                                :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")}
                               options)
                 mod (module/proxy-module config)]
             (rtest/launch-module! ipc mod {:tasks 2 :threads 2 :workers 2})
             (binding [*context* {:ipc ipc :config config :generation-release generation-release
                                  :coalesce-release coalesce-release :bounded-release bounded-release
                                  :activity activity :module-name (rama/get-module-name mod) :calls calls
                                  :release release :race-release race-release
                                  :client (aor/agent-client (aor/agent-manager ipc (rama/get-module-name mod)) "chat")}]
               (test-fn)))
           (finally (doseq [gate (vals gates)] (deliver gate true)))))
       (finally (.stop server 0) (.close ^java.util.concurrent.ExecutorService (.getExecutor server)))))))

(def ^:dynamic *require-complete-capture-inventory* false)

(defn with-single-map-captures [test-fn]
  (let [original admission/change!
        observed (atom {})
        violations (atom #{})]
    (with-redefs [admission/change! (fn [state f]
                                      (when-not (capture-test/single-map? f)
                                        (swap! violations conj (.getName (class f))))
                                      (swap! observed assoc (.getName (class f)) f)
                                      (original state f))]
      (test-fn))
    (is (empty? @violations) (str "Invalid persisted captures: " @violations))
    (doseq [f (vals @observed)] (capture-test/assert-single-map! f))
    (when-let [file (System/getenv "BRIDGE_METRICS_CORPUS")]
      (nippy/freeze-to-file file (into {} (filter #(str/starts-with? (key %) "bridge.replay$") @observed))))
    (when *require-complete-capture-inventory*
      (is (= 5 (count (filter #(str/starts-with? % "bridge.replay$") (keys @observed))))
          "All five replay metrics producer sites must execute"))))

(use-fixtures :once with-single-map-captures with-ipc)

(deftest nested-stream-reaches-client-in-order
  (let [{:keys [client]} *context*
        invoke (aor/agent-initiate client {:prompt "fixture"})
        complete (promise)]
    (with-open [_stream (aor/agent-stream client invoke "model"
                                          (fn [all _new _reset? done?] (when done? (deliver complete all))))]
      (let [result (aor/agent-result client invoke)]
        (is (= "one two" (:text result)))
        (is (= {:input 3 :output 2 :total 5} (:usage result))))
      (is (= ["one" " two"] (deref complete 10000 :timeout))))))

(deftest concurrent-identical-calls-share-stream-and-upstream
  (let [{:keys [client calls coalesce-release]} *context*
        before (count @calls)
        request {:prompt "coalesce" :call-id "coalesce"}
        first-call (aor/agent-initiate client request)
        first-chunk (promise) second-chunk (promise)
        first-done (promise) second-done (promise)]
    (with-open [_a (aor/agent-stream client first-call "model"
                                     (fn [all _ _ done?]
                                       (when (seq all) (deliver first-chunk true))
                                       (when done? (deliver first-done all))))]
      (try
        (is (= true (deref first-chunk 10000 :timeout)))
        (let [second-call (aor/agent-initiate client request)]
          (with-open [_b (aor/agent-stream client second-call "model"
                                           (fn [all _ _ done?]
                                             (when (seq all) (deliver second-chunk true))
                                             (when done? (deliver second-done all))))]
            (is (= true (deref second-chunk 10000 :timeout)))
            (is (= 1 (- (count @calls) before)))
            (deliver coalesce-release true)
            (is (= (aor/agent-result client first-call) (aor/agent-result client second-call)))
            (is (= ["one" " two"] (deref first-done 10000 :timeout)
                   (deref second-done 10000 :timeout)))))
        (finally (deliver coalesce-release true))))))

(defn eventually [pred]
  (loop [remaining 500]
    (if (pred)
      true
      (when (pos? remaining) (Thread/sleep 10) (recur (dec remaining))))))

(defn result-within [client invoke]
  (.get (aor/agent-result-async client invoke) 15 TimeUnit/SECONDS))

(deftest completion-between-binding-read-and-dispatch-replays-without-another-upstream
  (let [{:keys [client calls bounded-release]} *context*
        before (count @calls)
        request {:prompt "capture-race" :call-id "capture-race"}
        first-call (aor/agent-initiate client request)
        first-chunk (promise)
        original-get-store aor/get-store
        intercepted? (atom false)
        pending? (atom false)]
    (with-open [_stream (aor/agent-stream client first-call "model"
                                          (fn [all _ _ _] (when (seq all) (deliver first-chunk true))))]
      (try
        (is (= true (deref first-chunk 10000 :timeout)))
        ;; Pause a real external store read after it returns a pending binding.
        ;; The first transport completes before the second caller can join admission.
        (with-redefs [aor/get-store
                      (fn [node name]
                        (let [delegate (original-get-store node name)]
                          (if (not= "$$completed-calls" name)
                            delegate
                            (java.lang.reflect.Proxy/newProxyInstance
                             (.getClassLoader (class delegate))
                             (.getInterfaces (class delegate))
                             (reify java.lang.reflect.InvocationHandler
                               (invoke [_ _proxy method args]
                                 (let [entry (.invoke ^java.lang.reflect.Method method delegate args)]
                                   (when (and (= "pstate_select_one_STAR_" (.getName ^java.lang.reflect.Method method))
                                              (compare-and-set! intercepted? false true))
                                     (reset! pending? (and (some? entry) (nil? (:result entry))))
                                     (deliver (get bounded-release "capture-race") true)
                                     (result-within client first-call))
                                   entry)))))))]
          (let [second-call (aor/agent-initiate client request)]
            (is (= (result-within client first-call) (result-within client second-call)))))
        (is @intercepted?)
        (is @pending?)
        (is (= 1 (- (count @calls) before)))
        (finally (deliver (get bounded-release "capture-race") true))))))

(deftest default-admission-is-ten-active-fifty-fifo-and-typed-overload
  (let [{:keys [client ipc module-name calls bounded-release]} *context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        before (count @calls)
        invocations (atom [])
        pending #(count (remove :terminal? (vals (:entries (rama/foreign-select-one ["admission"] state)))))
        request (fn [n] {:prompt (str "bounded-" n) :call-id (str "bounded-" n)})]
    (try
      (doseq [n (range 60)]
        (swap! invocations conj (aor/agent-initiate client (request n)))
        ;; Public persisted state supplies an arrival barrier, not the FIFO assertion.
        (is (eventually #(= (inc n) (pending)))))
      (is (eventually #(>= (- (count @calls) before) 10)))
      (is (= 10 (- (count @calls) before)))
      (let [excess (aor/agent-initiate client (request 60))]
        (is (= {:error {:type :bridge.admission/overloaded}}
               (try (.get (aor/agent-result-async client excess) 5 TimeUnit/SECONDS)
                    (catch Exception _ :not-immediate-overload)))))
      ;; One completion admits exactly the next queued identity.
      (doseq [n (range 50)]
        (deliver (get bounded-release (str "bounded-" n)) true)
        (result-within client (nth @invocations n))
        (is (eventually #(>= (- (count @calls) before) (+ 11 n))))
        (is (= (+ 11 n) (- (count @calls) before)))
        (is (= (str "bounded-" (+ 10 n))
               (get-in (nth @calls (+ before 10 n)) [:messages 0 :content]))))
      (finally (doseq [[key gate] bounded-release :when (.startsWith ^String key "bounded-")] (deliver gate true))))
    (doseq [invoke @invocations]
      (is (= "one two" (:text (result-within client invoke)))))))

(deftest cancellation-completes-waiters-without-releasing-a-live-transport-slot
  (let [{:keys [client ipc module-name calls bounded-release]} *context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        before (count @calls)
        invocations (atom [])
        pending #(count (remove :terminal? (vals (:entries (rama/foreign-select-one ["admission"] state)))))
        request (fn [n] {:prompt (str "cancel-" n) :call-id (str "cancel-" n)})
        cancelled {:error {:type :bridge.admission/cancelled}}]
    (try
      (doseq [n (range 12)]
        (swap! invocations conj (aor/agent-initiate client (request n)))
        (is (eventually #(= (inc n) (pending)))))
      (is (eventually #(= 10 (- (count @calls) before))))
      (let [duplicate-active (aor/agent-initiate client (request 0))
            duplicate-queued (aor/agent-initiate client (request 10))
            cancel-client (aor/agent-client (aor/agent-manager ipc module-name) "cancel")]
        (is (eventually #(let [current (rama/foreign-select-one ["admission"] state)]
                           (= 14 (count (filter (fn [generation]
                                                  (let [entry (get-in current [:entries generation])]
                                                    (and entry (not (:terminal? entry)))))
                                                (vals (:waiters current))))))))
        (is (= {:cancelled true} (aor/agent-invoke cancel-client {:call-id "cancel-0"})))
        (is (= cancelled (result-within client (nth @invocations 0)) (result-within client duplicate-active)))
        (is (= 10 (- (count @calls) before)))
        (is (= {:cancelled true} (aor/agent-invoke cancel-client {:call-id "cancel-10"})))
        (is (= cancelled (result-within client (nth @invocations 10)) (result-within client duplicate-queued)))
        (is (= 10 (- (count @calls) before)))
        (deliver (get bounded-release "cancel-0") true)
        (is (eventually #(= 11 (- (count @calls) before))))
        (is (= "cancel-11" (get-in (last @calls) [:messages 0 :content])))
        (is (= cancelled (aor/agent-invoke client (request 0)))))
      (finally (doseq [[key gate] bounded-release :when (.startsWith ^String key "cancel-")] (deliver gate true))))
    (doseq [n (concat (range 1 10) [11])]
      (is (= "one two" (:text (result-within client (nth @invocations n))))))))

(deftest active-cancellation-survives-tombstone-expiry
  (let [{:keys [client ipc module-name calls bounded-release]} *context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        cancel-client (aor/agent-client (aor/agent-manager ipc module-name) "cancel")
        request {:prompt "expiry-cancel" :call-id "expiry-cancel"}
        before (count @calls)
        invoke (aor/agent-initiate client request)
        cancelled {:error {:type :bridge.admission/cancelled}}]
    (try
      (is (eventually #(= (inc before) (count @calls))))
      (with-redefs [admission/cancellation-retention-ms 100]
        (is (= {:cancelled true} (aor/agent-invoke cancel-client {:call-id "expiry-cancel"}))))
      (is (= cancelled (result-within client invoke)))
      (is (eventually #(nil? (rama/foreign-select-one ["admission" :cancellations "expiry-cancel"] state))))
      (is (= cancelled (result-within client (aor/agent-initiate client request))))
      (deliver (get bounded-release "expiry-cancel") true)
      (is (eventually #(let [deadline (rama/foreign-select-one ["admission" :cancellations "expiry-cancel"] state)]
                         (and deadline (> deadline (+ (System/currentTimeMillis) 3500000))))))
      (is (= 1 (- (count @calls) before)))
      (finally (deliver (get bounded-release "expiry-cancel") true)))))

(defn metric-value [observer metric]
  (Double/parseDouble (second (re-find (re-pattern (str "(?m)^" metric " ([0-9.]+)$"))
                                       (:body (obs-test/request (:port observer) "/metrics" "GET"))))))

(deftest worker-replacement-recovers-queued-calls-with-live-transports-still-accounted
  (let [{:keys [client ipc module-name config calls bounded-release activity]} *context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        observer (obs/start! {:snapshot #(rama/foreign-select-one ["admission"] state)})
        requests (metric-value observer "bridge_requests_total")
        before (count @calls)
        invocations (atom [])]
    (try
      (doseq [n (range 12)]
        (swap! invocations conj (aor/agent-initiate client {:prompt (str "restart-" n) :call-id (str "restart-" n)}))
        (is (eventually #(= (inc n) (count (remove :terminal? (vals (:entries (rama/foreign-select-one ["admission"] state)))))))))
      (is (eventually #(= 10 (- (count @calls) before))))
      (is (= 10.0 (metric-value observer "bridge_active_calls")))
      (is (= 2.0 (metric-value observer "bridge_queue_depth")))
      (let [updated (future (rtest/update-module! ipc (module/proxy-module config)) :updated)]
        (is (= :updated (deref updated 90000 :update-timeout)))
        (is (= 10 (- (count @calls) before)))
        (is (= 10 (:maximum @activity)))
        (is (= 10.0 (metric-value observer "bridge_active_calls")))
        (is (= 2.0 (metric-value observer "bridge_queue_depth")))
        (is (<= (+ requests 12) (metric-value observer "bridge_requests_total"))))
      ;; Free one slot at a time: simultaneous promotions may reach HTTP in
      ;; either order even though the durable queue admits them FIFO.
      (doseq [n (range 2)]
        (deliver (get bounded-release (str "restart-" n)) true)
        (is (or (eventually #(= (+ 11 n) (- (count @calls) before)))
                (do (println :restart-reservations
                             (mapv #(select-keys % [:call-id :phase :dispatched? :terminal? :error])
                                   (vals (:entries (rama/foreign-select-one ["admission"] state)))))
                    false)))
        (is (= (+ 11 n) (- (count @calls) before)))
        (is (= (str "restart-" (+ 10 n))
               (get-in (nth @calls (+ before 10 n)) [:messages 0 :content]))))
      (finally (obs/stop! observer) (doseq [[key gate] bounded-release :when (str/starts-with? key "restart-")] (deliver gate true))))
    (doseq [invoke @invocations]
      (is (= "one two" (:text (result-within client invoke)))))
    (is (= ["restart-10" "restart-11"]
           (filter #{"restart-10" "restart-11"} (map #(get-in % [:messages 0 :content]) @calls))))
    (is (<= (:maximum @activity) 10))))

(deftest cancellation-tombstones-expire-without-new-traffic
  (let [{:keys [ipc module-name]} *context*
        cancel-client (aor/agent-client (aor/agent-manager ipc module-name) "cancel")
        state (rama/foreign-pstate ipc module-name "$$admission")]
    (with-redefs [admission/cancellation-retention-ms 200]
      (is (= {:cancelled true} (aor/agent-invoke cancel-client {:call-id "cancel-expiry"}))))
    (is (eventually #(nil? (rama/foreign-select-one ["admission" :cancellations "cancel-expiry"] state))))))

(deftest admission-depot-redelivery-does-not-repeat-chunks-or-resurrect-retired-work
  (let [{:keys [ipc module-name]} *context*
        depot (rama/foreign-depot ipc module-name "*admission-changes")
        state (rama/foreign-pstate ipc module-name "$$admission")
        observer (obs/start! {:snapshot #(rama/foreign-select-one ["admission"] state)})
        coalesces (metric-value observer "bridge_coalescing_total")
        generation "redelivery-fixture"
        now (System/currentTimeMillis)
        command (fn [f] {:key "admission" :id (str (java.util.UUID/randomUUID))
                         :expires-at (+ now 2000) :transform f})
        registration (command #(admission/register % ["redelivery" "binding"] "redelivery" generation
                                                   {:active-limit 10 :queue-limit 50} now))
        join (command #(admission/register % ["redelivery" "binding"] "redelivery" "duplicate-redelivery"
                                           {:active-limit 10 :queue-limit 50} now))
        chunk (command #(update-in % [:entries generation :chunks] conj "one"))
        retirement (assoc (command #(-> (admission/complete % generation {:terminal? true :expires-at (+ now 3600000)})
                                        (update :waiters dissoc generation "duplicate-redelivery")
                                        (update :entries dissoc generation)))
                          :expires-at (+ now 3600000))]
    (try
      (is (= [:applied] (vec (vals (rama/foreign-append! depot registration)))))
      (rama/foreign-append! depot join)
      (rama/foreign-append! depot join)
      (is (= 1.0 (- (metric-value observer "bridge_coalescing_total") coalesces)))
      (rama/foreign-append! depot chunk)
      (rama/foreign-append! depot chunk)
      (is (= ["one"] (rama/foreign-select-one ["admission" :entries generation :chunks] state)))
      (rama/foreign-append! depot retirement)
      (rama/foreign-append! depot registration)
      (is (nil? (rama/foreign-select-one ["admission" :entries generation] state)))
      (is (eventually #(<= (:expires-at registration) (System/currentTimeMillis))))
      (let [receipts (rama/foreign-pstate ipc module-name "$$admission-receipts")]
        (is (eventually #(nil? (rama/foreign-select-one [(:id registration)] receipts {:pkey "admission"})))))
      (is (= [:expired] (vec (vals (rama/foreign-append! depot registration)))))
      (is (nil? (rama/foreign-select-one ["admission" :entries generation] state)))
      (finally (rama/foreign-append! depot retirement) (obs/stop! observer)))))

(deftest identical-in-flight-call-survives-binding-expiry-and-replays-after-completion
  (let [{:keys [client ipc module-name calls bounded-release]} *context*
        state (rama/foreign-pstate ipc module-name "$$completed-calls")
        request {:prompt "expiry-coalesce" :call-id "expiry-coalesce"}
        before (count @calls)
        first-call (with-redefs [replay/binding-retention-ms 200]
                     (let [invoke (aor/agent-initiate client request)]
                       (is (eventually #(= (inc before) (count @calls))))
                       invoke))]
    (try
      (is (eventually #(nil? (rama/foreign-select-one ["expiry-coalesce/0"] state))))
      (let [second-call (aor/agent-initiate client request)
            caught-up (promise)]
        (with-open [_stream (aor/agent-stream client second-call "model"
                                              (fn [all _ _ _] (when (seq all) (deliver caught-up all))))]
          (is (= ["one"] (deref caught-up 10000 :timeout)))
          (is (= 1 (- (count @calls) before)))
          (deliver (get bounded-release "expiry-coalesce") true)
          (is (= (result-within client first-call) (result-within client second-call)))
          (is (= "one two" (:text (aor/agent-invoke client request))))
          (is (= 1 (- (count @calls) before)))))
      (finally (deliver (get bounded-release "expiry-coalesce") true)))))

(deftest completion-depot-redelivery-keeps-first-result-and-cannot-revive-expired-success
  (let [{:keys [ipc module-name client]} *context*
        depot (rama/foreign-depot ipc module-name "*completed-changes")
        state (rama/foreign-pstate ipc module-name "$$completed-calls")
        identity "completion-command-fixture"
        command {:identity identity :fingerprint "fixture-digest" :generation (str (java.util.UUID/randomUUID))
                 :result {:fixture "first"} :chunks ["one"] :expires-at (+ (System/currentTimeMillis) 2000)}]
    (with-redefs [replay/retention-ms 2000]
      (rama/foreign-append! depot command)
      (let [saved (rama/foreign-select-one [identity] state)]
        (is (= {:fixture "first"} (:result saved)))
        (rama/foreign-append! depot (assoc command :result {:fixture "second"} :expires-at (inc (:expires-at command))))
        (is (= saved (rama/foreign-select-one [identity] state))))
      (is (eventually #(nil? (rama/foreign-select-one [identity] state))))
      (rama/foreign-append! depot command)
      (is (empty? (rama/foreign-select [(path/must identity)] state {:pkey identity})))
      (is (= "one two" (:text (result-within client (aor/agent-initiate client {:prompt "fixture"}))))))))

(deftest completion-recreates-expired-binding-before-releasing-admission
  (let [{:keys [client ipc module-name calls bounded-release]} *context*
        state (rama/foreign-pstate ipc module-name "$$completed-calls")
        request {:prompt "completion-expiry" :call-id "completion-expiry"}
        before (count @calls)
        invoke (with-redefs [replay/binding-retention-ms 200]
                 (let [invoke (aor/agent-initiate client request)]
                   (is (eventually #(= (inc before) (count @calls))))
                   invoke))]
    (try
      (is (eventually #(nil? (rama/foreign-select-one ["completion-expiry/0"] state))))
      (deliver (get bounded-release "completion-expiry") true)
      (is (= "one two" (:text (result-within client invoke))))
      (is (some? (:result (rama/foreign-select-one ["completion-expiry/0"] state))))
      (is (= "one two" (:text (aor/agent-invoke client request))))
      (is (= 1 (- (count @calls) before)))
      (finally (deliver (get bounded-release "completion-expiry") true)))))

(defn failure-within [client invoke]
  (try (result-within client invoke) :unexpected-success
       (catch TimeoutException _ :stranded-waiter)
       (catch ExecutionException _ :failed)))

(deftest rama-retry-replays-completed-call
  (let [{:keys [client calls ipc module-name]} *context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        observer (obs/start! {:snapshot #(rama/foreign-select-one ["admission"] state)})
        retries (metric-value observer "bridge_observed_retries_total")
        before (count @calls)]
    (try
      (is (= "one two" (:text (aor/agent-invoke client {:prompt "fixture" :force-retry? true}))))
      (is (= 1.0 (- (metric-value observer "bridge_observed_retries_total") retries)))
      (is (= 1 (- (count @calls) before)))
      (finally (obs/stop! observer)))))

(deftest tool-roundtrip-returns-executed-result-to-model
  (let [{:keys [client calls]} *context* before (count @calls)]
    (is (= "ping" (:text (aor/agent-invoke client {:prompt "fixture" :tool? true}))))
    (is (= 2 (- (count @calls) before)))
    (is (= {:role "tool" :tool_call_id "call_fixture" :content "ping"}
           (first (filter #(= "tool" (:role %)) (:messages (last @calls))))))))

(deftest model-timeout-propagates-through-agent
  (let [{:keys [client ipc module-name calls]} *context*
        invoke (aor/agent-initiate client {:prompt "stall" :timeout? true})]
    (is (thrown? Exception (aor/agent-result client invoke)))
    (is (some #(= "stall" (get-in % [:messages 0 :content])) @calls))
    (is (inspect/expected-failure? (inspect/model-operations ipc module-name client invoke) :timeout))))

(deftest saved-trace-contains-model-response-and-usage
  (let [{:keys [ipc module-name client]} *context*
        invoke (aor/agent-initiate client {:prompt "fixture"})]
    (aor/agent-result client invoke)
    (let [ops (inspect/model-operations ipc module-name client invoke)
          info (:info (first ops))]
      (is (= 1 (count ops)))
      (is (= "one two" (get info "response")))
      (is (= [3 2 5] (mapv info ["inputTokenCount" "outputTokenCount" "totalTokenCount"])))
      (is (number? (get info "firstTokenTimeMillis"))))))

(deftest closing-subscription-does-not-cancel-model-call
  (let [{:keys [client release]} *context*
        first-chunk (promise) callbacks (atom 0)
        invoke (aor/agent-initiate client {:prompt "pause"})
        stream (aor/agent-stream client invoke "model"
                                 (fn [_all new _reset? _done?]
                                   (swap! callbacks inc)
                                   (when (seq new) (deliver first-chunk true))))]
    (try
      (is (= true (deref first-chunk 10000 :timeout)))
      (.close stream)
      (let [before @callbacks]
        (deliver release true)
        (is (= "one two" (:text (aor/agent-result client invoke))))
        (is (= before @callbacks)))
      (finally (deliver release true) (.close stream)))))

(deftest provider-error-propagates-through-agent
  (let [{:keys [client ipc module-name calls]} *context*
        invoke (aor/agent-initiate client {:prompt "fixture" :model-name "spike-nonexistent-model"})]
    (is (thrown? Exception (aor/agent-result client invoke)))
    (is (some #(= "spike-nonexistent-model" (:model %)) @calls))
    (is (inspect/expected-failure? (inspect/model-operations ipc module-name client invoke) :http-400))))

(deftest expired-completions-are-removed-without-another-request
  (let [{:keys [client ipc module-name calls]} *context*
        state (rama/foreign-pstate ipc module-name "$$completed-calls")
        key "expiry/0"
        before (count @calls)
        original (with-redefs [replay/retention-ms 5000]
                   (aor/agent-invoke client {:prompt "fixture" :call-id "expiry"}))
        deadline (:expires-at (rama/foreign-select-one [key] state))]
    (is (number? deadline))
    (is (= original (aor/agent-invoke client {:prompt "fixture" :call-id "expiry"})))
    (is (= deadline (:expires-at (rama/foreign-select-one [key] state))))
    (is (nil? (loop [remaining 200]
                (let [entry (rama/foreign-select-one [key] state)]
                  (if (and entry (pos? remaining))
                    (do (Thread/sleep 50) (recur (dec remaining)))
                    entry)))))
    (is (= "new" (:text (aor/agent-invoke client {:prompt "generation-new" :call-id "expiry"}))))
    (is (= 2 (- (count @calls) before)))))

(deftest reused-identity-returns-typed-conflict
  (let [{:keys [client calls]} *context* before (count @calls)]
    (aor/agent-invoke client {:prompt "fixture" :call-id "conflict"})
    (is (= {:error {:type :bridge.replay/identity-conflict}}
           (try (aor/agent-invoke client {:prompt "different" :call-id "conflict"})
                (catch Exception _ :untyped-exception))))
    (is (= 1 (- (count @calls) before)))))

(deftest malformed-or-unsupported-request-is-rejected-before-upstream
  (let [{:keys [client calls]} *context* before (count @calls)]
    (doseq [request [{:prompt "fixture" :call-id 1}
                     {:prompt "fixture" :call-id ""}
                     {:prompt "fixture" :temperature 0.5}]]
      (is (= {:error {:type :bridge.replay/invalid-request}}
             (try (aor/agent-invoke client request) (catch Exception _ :untyped-exception)))))
    (is (= before (count @calls)))))

(deftest canonical-fingerprint-distinguishes-semantic-schema-types
  (let [request (fn [shape] (lc4j/chat-request ["fixture"]
                                               {:response-format (lc4j/json-response-format "result" shape)}))]
    (is (not= (replay/fingerprint (request (schema/string)) {})
              (replay/fingerprint (request (schema/boolean)) {})))))

(deftest different-in-flight-request-cannot-reuse-identity
  (let [{:keys [client calls race-release]} *context*
        before (count @calls)
        first-call (aor/agent-initiate client {:prompt "identity-race" :call-id "in-flight"})]
    (try
      (is (eventually #(= (inc before) (count @calls))))
      (is (not (aor/agent-invoke-complete? client first-call)))
      (let [second-call (aor/agent-initiate client {:prompt "different" :call-id "in-flight"})
            ;; The gate proves overlap. A full-suite diagnostic observed a valid
            ;; conflict by 1.10s, after the former one-second deadline expired.
            result (try (result-within client second-call)
                        (catch Exception error
                          {:unexpected-exception (.getName (class error))
                           :first-released? (realized? race-release)
                           :upstream-dispatches (- (count @calls) before)}))]
        (is (= {:error {:type :bridge.replay/identity-conflict}} result))
        (is (not (realized? race-release)))
        (is (not (aor/agent-invoke-complete? client first-call)))
        (is (= 1 (- (count @calls) before))))
      (finally (deliver race-release true)))
    (is (= "one two" (:text (result-within client first-call))))
    (is (= 1 (- (count @calls) before)))))

(defn streamed-result [client request]
  (let [invoke (aor/agent-initiate client request)
        completed (promise)]
    (with-open [stream (aor/agent-stream client invoke "model"
                                         (fn [all _new _reset? done?]
                                           (when done? (deliver completed all))))]
      {:invoke invoke :result (aor/agent-result client invoke)
       :chunks (deref completed 10000 :timeout)
       :resets (aor/agent-stream-reset-info stream)})))

(deftest completed-tool-exchange-replays-serialized-tool-calls-and-chunks
  (let [{:keys [client calls]} *context*
        before (count @calls)
        request {:prompt "fixture" :tool? true :call-id "tool-replay"}
        original (streamed-result client request)
        replayed (streamed-result client request)]
    (is (= "ping" (get-in replayed [:result :text])))
    (is (= (select-keys original [:result :chunks]) (select-keys replayed [:result :chunks])))
    (is (= 2 (- (count @calls) before)))))

(deftest worker-replacement-retains-completed-replay-and-rebuilds-agent-objects
  (let [{:keys [client calls ipc module-name config]} *context*
        before (count @calls)
        request {:prompt "fixture" :call-id "worker-restart" :force-retry? true}
        original (streamed-result client request)]
    (rtest/update-module! ipc (module/proxy-module config))
    (let [replayed (streamed-result client request)]
      (is (= (select-keys original [:result :chunks]) (select-keys replayed [:result :chunks])))
      (is (= 1 (:resets replayed)))
      (is (= 2 (count (inspect/model-operations ipc module-name client (:invoke replayed)))))
      (is (= 1 (- (count @calls) before))))))

(deftest expired-generation-cannot-overwrite-a-new-completion
  (let [{:keys [client calls generation-release ipc module-name]} *context*
        before (count @calls)
        first-call (with-redefs [replay/binding-retention-ms 200]
                     (let [invoke (aor/agent-initiate client {:prompt "generation-first" :call-id "generation"})]
                       (loop [remaining 200]
                         (when (and (= before (count @calls)) (pos? remaining))
                           (Thread/sleep 10) (recur (dec remaining))))
                       invoke))]
    (try
      (is (= (inc before) (count @calls)))
      (let [state (rama/foreign-pstate ipc module-name "$$completed-calls")]
        (loop [remaining 100]
          (when (and (rama/foreign-select-one ["generation/0"] state) (pos? remaining))
            (Thread/sleep 20) (recur (dec remaining)))))
      (is (= "new" (:text (aor/agent-invoke client {:prompt "generation-new" :call-id "generation"}))))
      (finally (deliver generation-release true)))
    (is (= "one two" (:text (aor/agent-result client first-call))))
    (is (= "new" (:text (aor/agent-invoke client {:prompt "generation-new" :call-id "generation"}))))
    (is (= 2 (- (count @calls) before)))))

(deftest fingerprint-covers-request-fields-and-canonicalizes-object-order
  (let [base (replay/fingerprint (lc4j/chat-request ["fixture"]) {})]
    (doseq [options [{:temperature 0.1} {:top-p 0.2} {:top-k 2}
                     {:frequency-penalty 0.3} {:presence-penalty 0.4}
                     {:max-output-tokens 20} {:model-name "other"}
                     {:stop-sequences ["stop"]} {:tool-choice :required}
                     {:tools [module/echo-tool]}]]
      (is (not= base (replay/fingerprint (lc4j/chat-request ["fixture"] options) {}))))
    (is (not= base (replay/fingerprint (lc4j/chat-request ["other"]) {})))
    (is (not= base (replay/fingerprint (lc4j/chat-request ["fixture"]) {:model "other"}))))
  (let [request (fn [properties]
                  (lc4j/chat-request ["fixture"]
                                     {:response-format (lc4j/json-response-format
                                                        "result" (schema/object properties))}))
        a ["a" (schema/string)] b ["b" (schema/boolean)]]
    (is (= (replay/fingerprint (request (into (array-map) [a b])) {:a 1 :b 2})
           (replay/fingerprint (request (into (array-map) [b a])) {:b 2 :a 1}))))
  (is (= 3600000 replay/retention-ms)))

(deftest observer-counts-global-model-outcomes
  (let [{:keys [client ipc module-name]} *context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        observer (obs/start! {:snapshot #(rama/foreign-select-one [admission/state-key] state)})
        scrape #(:body (obs-test/request (:port observer) "/metrics" "GET"))
        value (fn [metric] (Double/parseDouble (or (second (re-find (re-pattern (str "(?m)^" metric " ([0-9.]+)$")) (scrape))) "0")))
        before (into {} (for [metric ["bridge_requests_total" "bridge_replay_hits_total" "bridge_conflicts_total" "bridge_observed_retries_total" "bridge_upstream_dispatches_total" "bridge_request_duration_seconds_count"]] [metric (value metric)]))]
    (try
      (aor/agent-invoke client {:prompt "fixture" :call-id "metrics-one"})
      (aor/agent-invoke client {:prompt "fixture" :call-id "metrics-one"})
      (aor/agent-invoke client {:prompt "different-sensitive-prompt" :call-id "metrics-one"})
      (is (= 3.0 (- (value "bridge_requests_total") (before "bridge_requests_total"))))
      (is (= 1.0 (- (value "bridge_replay_hits_total") (before "bridge_replay_hits_total"))))
      (is (= 1.0 (- (value "bridge_conflicts_total") (before "bridge_conflicts_total"))))
      (is (= (before "bridge_observed_retries_total") (value "bridge_observed_retries_total")))
      (is (= 1.0 (- (value "bridge_upstream_dispatches_total") (before "bridge_upstream_dispatches_total"))))
      (is (= 3.0 (- (value "bridge_request_duration_seconds_count") (before "bridge_request_duration_seconds_count"))))
      (let [counts (map #(Double/parseDouble (second %))
                        (re-seq #"(?m)^bridge_request_duration_seconds_bucket\{le=\"[^\"]+\"\} ([0-9.]+)$" (scrape)))]
        (is (= 9 (count counts)))
        (is (apply <= counts))
        (is (= (last counts) (value "bridge_request_duration_seconds_count"))))
      (is (not (str/includes? (scrape) "different-sensitive-prompt")))
      (is (not (str/includes? (scrape) "metrics-one")))
      (finally (obs/stop! observer)))))

(defn -main [& _]
  (require 'bridge.admission-config-test 'bridge.runtime-test)
  (let [{:keys [fail error]} (binding [*require-complete-capture-inventory* true]
                               (run-tests 'bridge.ipc-test 'bridge.admission-config-test 'bridge.locks-test 'bridge.observability-test 'bridge.runtime-test 'bridge.capture-test))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
