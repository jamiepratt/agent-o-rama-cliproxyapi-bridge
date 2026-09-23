(ns bridge.runtime-test
  (:require [clojure.test :refer [deftest is]]
            [bridge.runtime :as runtime]
            [bridge.observability :as obs]
            [bridge.ipc-test :as fixtures]
            [com.rpl.agent-o-rama :as aor]))

(deftest deployment-rejects-public-or-ephemeral-lock-config
  (let [options {:base-url "http://127.0.0.1:18317/v1" :model "gpt-6-luna"
                 :bearer-file "/var/lib/bridge-rama/bearer"
                 :lock-dir "/var/lib/bridge-rama/locks"}]
    (is (= options (runtime/validate-config options)))
    (doseq [bad [(assoc options :base-url "http://0.0.0.0:18317/v1")
                 (assoc options :lock-dir "/tmp/locks")
                 (dissoc options :bearer-file)
                 (assoc options :api-key "must-not-serialize")
                 (assoc options :timeout-ms -1)]]
      (is (thrown? clojure.lang.ExceptionInfo (runtime/validate-config bad))))))

(deftest production-observer-reads-the-actual-module-name
  (fixtures/with-ipc
    (fn []
      (let [{:keys [ipc config client]} fixtures/*context*
            observer (runtime/start-observer! ipc config)]
        (try
          (is (re-find #"bridge_active_calls 0" (slurp "http://127.0.0.1:18318/metrics")))
          (is (re-find #"not-configured" (slurp "http://127.0.0.1:18318/ready")))
          (aor/agent-invoke client {:prompt "fixture" :call-id "deployment-observer"})
          (is (re-find #"bridge_requests_total 1" (slurp "http://127.0.0.1:18318/metrics")))
          (is (re-find #"bridge_upstream_dispatches_total 1" (slurp "http://127.0.0.1:18318/metrics")))
          (finally (obs/stop! observer)))))))

(deftest failed-ui-start-does-not-leave-the-observer-listening
  (fixtures/with-ipc
    (fn []
      (let [{:keys [ipc config]} fixtures/*context*]
       ;; External UI-server failure, without opening its wildcard listener locally.
        (with-redefs [aor/start-ui (fn [& _] (throw (ex-info "UI startup failed" {})))]
          (is (thrown? Exception (runtime/start-services! ipc config))))
       ;; Binding succeeds only if the failed startup released its HTTP server.
        (let [observer (runtime/start-observer! ipc config)]
          (try (is (re-find #"bridge_active_calls 0" (slurp "http://127.0.0.1:18318/metrics")))
               (finally (obs/stop! observer))))))))
