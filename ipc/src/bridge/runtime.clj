(ns bridge.runtime
  "Production-side clients and strict non-secret configuration. No IPC cluster."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [bridge.observability :as obs]
            [com.rpl.rama :as rama]
            [com.rpl.agent-o-rama :as aor])
  (:import [java.lang AutoCloseable]
           [com.rpl.rama RamaClusterManager]
           [java.util.concurrent TimeUnit]))

(defn validate-config [options]
  (when-not
   (and (map? options)
        (every? #{:base-url :model :bearer-file :lock-dir :timeout-ms :deadline-ms
                  :sweep-ms :active-limit :queue-limit} (keys options))
        (= "http://127.0.0.1:18317/v1" (:base-url options))
        (string? (:model options)) (not (str/blank? (:model options)))
        (= "/var/lib/bridge-rama/bearer" (:bearer-file options))
        (= "/var/lib/bridge-rama/locks" (:lock-dir options))
        (every? #(or (not (contains? options %)) (pos-int? (get options %)))
                [:timeout-ms :deadline-ms :sweep-ms :active-limit])
        (or (not (contains? options :queue-limit))
            (and (integer? (:queue-limit options)) (<= 0 (:queue-limit options)))))
    (throw (ex-info "Invalid private deployment configuration" {:type ::invalid-config})))
  options)

(defn read-config []
  (validate-config (edn/read-string (slurp "/etc/bridge/module.edn"))))

(defn start-observer! [cluster config]
  (let [state (rama/foreign-pstate cluster "bridge.module/ProxyModule" "$$admission")]
    (obs/start! (merge (select-keys config [:base-url :model :bearer-file])
                       {:port 18318 :provider :openai-compatible :configured? true
                        :timeout-ms 15000 :refresh-ms 60000 :verification-ttl-ms 300000
                        :snapshot #(let [pending (rama/foreign-select-one-async ["admission"] state)]
                                     (try (.get pending 14000 TimeUnit/MILLISECONDS)
                                          (finally (.cancel pending true))))}))))

(defn start-services! [cluster config]
  (let [observer (start-observer! cluster config)]
    (try
      ;; Pinned 0.10.0 ignores :host. The mandatory host firewall protects this UI.
      (let [ui (aor/start-ui cluster {:port 1974 :no-input-before-close true})]
        (fn [] (try (.close ^AutoCloseable ui)
                    (finally (obs/stop! observer)))))
      (catch Throwable failure
        (obs/stop! observer)
        (throw failure)))))

(defn -main [& _]
  (let [config (read-config)
        cluster (RamaClusterManager/open {"conductor.host" "127.0.0.1"})
        stop-services (try (start-services! cluster config)
                           (catch Throwable failure
                             (.close cluster)
                             (throw failure)))
        stopped (promise)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(try (stop-services)
                                     (finally (.close cluster)
                                              (deliver stopped true)))))
    @stopped))
