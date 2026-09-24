(ns probe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.edn :as edn]
            [probe :as probe]))
(def sample {:text "OK" :chunks ["O" "K"] :usage {:input 3 :output 1 :total 4}})
(deftest warm-cold-replay
  (let [record (.resolve (java.nio.file.Files/createTempDirectory "bridge-probe-test" (make-array java.nio.file.attribute.FileAttribute 0)) "record.edn")
        count* (atom 0)
        stored (atom {})
        stream (fn [_ id]
                 (or (get @stored id)
                     (do (swap! count* inc) (swap! stored assoc id sample) sample)))]
    (with-redefs [probe/dispatches (fn [] @count*)
                  probe/stream! stream]
      (let [warm (probe/run-probe! nil "warm" (str record) "reboot-check")
            cold (probe/run-probe! nil "cold" (str record) "reboot-check")]
        (is (= 1 (:new_dispatches warm)))
        (is (= 0 (:replay_dispatches warm)))
        (is (= 0 (:replay_dispatches cold)))
        (is (= 1 (:new_dispatches cold)))
        (is (= 0 (:new_dispatches (probe/run-probe! nil "replay" (str record) "reboot-check"))))
        (is (thrown? java.nio.file.FileAlreadyExistsException (probe/run-probe! nil "warm" (str record) "reboot-check")))
        (with-redefs [probe/stream! (fn [_ _] (assoc sample :text "changed"))]
          (is (thrown? clojure.lang.ExceptionInfo (probe/run-probe! nil "replay" (str record) "reboot-check"))))
        (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString (java.nio.file.Files/getPosixFilePermissions record (make-array java.nio.file.LinkOption 0)))))
        (is (= sample (:sample (edn/read-string (slurp (str record))))))
        (with-redefs [probe/stream! (fn [_ _] (swap! count* inc) sample)]
          (is (thrown? clojure.lang.ExceptionInfo (probe/run-probe! nil "cold" (str record) "reboot-check"))))))))
(let [r (run-tests)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))
