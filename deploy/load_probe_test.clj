(ns load-probe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [load-probe :as probe]
            [com.rpl.agent-o-rama :as aor])
  (:import [java.util.concurrent CompletableFuture]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))
(deftest bounded-load-releases-and-drains
  (let [root (str (Files/createTempDirectory "bridge-load-test" (make-array FileAttribute 0)))
        initiated (atom 0)
        observations (atom 0)]
    (spit (io/file root "start") "")
    (with-redefs [aor/agent-initiate (fn [_ _] (swap! initiated inc))
                  aor/agent-result-async (fn [_ _] (CompletableFuture/completedFuture {:text "OK"}))
                  probe/metrics (fn [] (if (= 1 (swap! observations inc))
                                         {:active_calls 10 :queue_depth 50}
                                         {:active_calls 0 :queue_depth 0}))]
      (let [report (probe/run-load! nil root)]
        (is (:ok report))
        (is (= 60 @initiated (:completed report)))
        (is (.exists (io/file root "client-ready")))
        (is (.exists (io/file root "release")))))))
(let [r (run-tests)] (shutdown-agents) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))
