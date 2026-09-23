(ns bridge.admission-config-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.rpl.agent-o-rama :as aor]
            [com.rpl.rama :as rama]
            [bridge.ipc-test :as ipc]))

(use-fixtures :once #(ipc/with-ipc {:active-limit 1 :queue-limit 1 :deadline-ms 3000} %))

(deftest configured-limits-and-shared-failures-release-capacity
  (let [{:keys [client ipc module-name calls bounded-release]} ipc/*context*
        state (rama/foreign-pstate ipc module-name "$$admission")
        first-call (aor/agent-initiate client {:prompt "small-0" :call-id "small-0"})]
    (is (ipc/eventually #(= 1 (count @calls))))
    (let [queued (aor/agent-initiate client {:prompt "small-1" :call-id "small-1"})]
      (is (ipc/eventually #(= 1 (count (:queue (rama/foreign-select-one ["admission"] state))))))
      (is (= {:error {:type :bridge.admission/overloaded}}
             (ipc/result-within client (aor/agent-initiate client {:prompt "small-2" :call-id "small-2"}))))
      (is (= 1 (count @calls)))
      (deliver (get bounded-release "small-0") true)
      (is (= "one two" (:text (ipc/result-within client first-call))))
      (is (ipc/eventually #(= 2 (count @calls))))
      (deliver (get bounded-release "small-1") true)
      (is (= "one two" (:text (ipc/result-within client queued)))))
    (doseq [request [{:prompt "failure-shared" :call-id "shared-error" :model-name "spike-nonexistent-model"}
                     {:prompt "deadline-shared" :call-id "shared-timeout" :timeout? true}]]
      (let [before (count @calls)
            original (aor/agent-initiate client request)]
        (is (ipc/eventually #(= (inc before) (count @calls))))
        (let [duplicate (aor/agent-initiate client request)]
          (is (ipc/eventually #(= 2 (count (:waiters (rama/foreign-select-one ["admission"] state))))))
          (is (= 1 (- (count @calls) before)))
          (let [healthy (aor/agent-initiate client {:prompt "fixture"})]
            (is (ipc/eventually #(= 1 (count (:queue (rama/foreign-select-one ["admission"] state))))))
            (when (= "failure-shared" (:prompt request))
              (deliver (get bounded-release "failure-shared") true))
            (is (= "one two" (:text (ipc/result-within client healthy))))
            (is (= :failed (ipc/failure-within client original) (ipc/failure-within client duplicate)))
            (is (ipc/eventually #(empty? (:active (rama/foreign-select-one ["admission"] state)))))))))))
