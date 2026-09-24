(ns load-probe
  "Run with a new private ROOT after stopping proxy and starting load_fixture.py.
  Waits for ROOT/start, submits 60 calls, releases fixture by 20s, then drains."
  (:require [com.rpl.agent-o-rama :as aor]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [com.rpl.rama RamaClusterManager]
           [java.util.concurrent TimeUnit]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

(def stage (atom "startup"))
(defn mark! [root name] (spit (io/file root name) ""))
(defn metrics []
  (let [connection (.openConnection (.toURL (java.net.URI. "http://127.0.0.1:18318/metrics")))]
    (.setConnectTimeout connection 1000)
    (.setReadTimeout connection 2000)
    (with-open [in (.getInputStream connection)]
      (let [s (slurp in)
            value (fn [suffix] (Long/parseLong (second (re-find (re-pattern (str "(?m)^bridge_" suffix " ([0-9]+)$")) s))))]
        {:active_calls (value "active_calls") :queue_depth (value "queue_depth")}))))
(defn remaining-ms [deadline]
  (max 1 (long (/ (- deadline (System/nanoTime)) 1000000))))
(defn run-load! [client root]
  (mark! root "client-ready")
  (reset! stage "await-fixture")
  (loop [n 600]
    (when-not (.exists (io/file root "start"))
      (when (zero? n) (throw (ex-info "Start deadline" {})))
      (Thread/sleep 100) (recur (dec n))))
  (reset! stage "load")
  (let [deadline (+ (System/nanoTime) 20000000000)
        release (future (Thread/sleep 20000) (mark! root "release"))
        prefix (str "deployment-load-" (java.util.UUID/randomUUID))
        pending (mapv (fn [n] (future (aor/agent-initiate client {:prompt "Reply OK." :call-id (str prefix "-" n)}))) (range 60))]
    (try
      (let [invokes (mapv #(.get % (remaining-ms deadline) TimeUnit/MILLISECONDS) pending)
            observed (loop [peak-active 0 peak-queue 0]
                       (let [{a :active_calls q :queue_depth} (metrics)
                             pa (max a peak-active) pq (max q peak-queue)]
                         (if (or (and (= 10 a) (= 50 q)) (>= (System/nanoTime) deadline))
                           {:ten_active_fifty_queued (and (= 10 a) (= 50 q)) :peak_active pa :peak_queue pq}
                           (do (Thread/sleep 100) (recur pa pq)))))]
        (mark! root "release")
        (reset! stage "drain")
        (let [drain-deadline (+ (System/nanoTime) 90000000000)
              results (mapv #(try (.get (aor/agent-result-async client %) (remaining-ms drain-deadline) TimeUnit/MILLISECONDS)
                                  (catch Throwable error {:failed_class (.getName (class error))})) invokes)
              successes (count (filter #(= "OK" (:text %)) results))
              final-metrics (metrics)]
          (assoc observed :ok (and (:ten_active_fifty_queued observed) (= 60 successes)
                                   (= {:active_calls 0 :queue_depth 0} final-metrics))
                 :completed successes :failure_classes (frequencies (keep :failed_class results)) :final final-metrics)))
      (finally
        (mark! root "release")
        (future-cancel release)
        (doseq [p pending] (future-cancel p))))))

(defn -main [& args]
  (let [root (first args)]
    (try
      (when-not (= 1 (count args)) (throw (ex-info "Usage: load_probe.clj ROOT" {})))
      (let [path (.toPath (io/file root))]
        (when-not (and (.isAbsolute path) (not (Files/isSymbolicLink path))
                       (Files/isDirectory path (make-array LinkOption 0))
                       (= "rwx------" (PosixFilePermissions/toString (Files/getPosixFilePermissions path (make-array LinkOption 0))))
                       (not-any? #(.exists (io/file root %)) ["client-ready" "start" "release"]))
          (throw (ex-info "New private root required" {}))))
      (with-open [cluster (RamaClusterManager/open {"conductor.host" "127.0.0.1"})]
        (let [client (aor/agent-client (aor/agent-manager cluster "bridge.module/ProxyModule") "chat")
              report (run-load! client root)]
          (println (json/write-str report))
          (shutdown-agents)
          (System/exit (if (:ok report) 0 1))))
      (catch Throwable error
        (println (json/write-str {:ok false :stage @stage :error_class (.getName (class error))}))
        (shutdown-agents) (System/exit 1)))))
(when (seq *command-line-args*) (apply -main *command-line-args*))
