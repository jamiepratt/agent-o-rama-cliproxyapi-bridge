(ns bridge.commands-test
  (:require [bridge.commands :as commands]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]
            [taoensso.nippy :as nippy])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn cold-process! [mode path perturb]
  (let [args [(str (System/getProperty "java.home") "/bin/java")
              "-Xmx512m" "-cp" (System/getProperty "java.class.path")
              "clojure.main" "-e"
              (str "(dotimes [_ " perturb "] (System/identityHashCode (Object.)))"
                   "(require 'bridge.commands-test)"
                   "(bridge.commands-test/cold-main " (pr-str mode) " " (pr-str path) ")")]
        process (.start (ProcessBuilder. ^java.util.List args))]
    (try
      (when-not (.waitFor process 90 TimeUnit/SECONDS)
        (throw (ex-info "Cold command process timed out" {})))
      {:exit (.exitValue process)
       :out (slurp (.getInputStream process))
       :err (slurp (.getErrorStream process))}
      (finally (.destroyForcibly process)))))

(defn rejection [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error
         [(ex-message error) (ex-data error)])))

(defn cold-main [mode path]
  (case mode
    "write" (nippy/freeze-to-file
             path [(commands/command :admission/chunk
                                     (with-meta {:generation "generation-A" :chunk "chunk-B"}
                                       {:capture (fn [] "never serialize me")}))
                   (commands/command :admission/leave {:generation "generation-A" :candidate "waiter-C"})
                   (commands/command :admission/cancel {:call-id "call-D" :now 100})
                   (commands/command :replay/reserve {:fingerprint "fingerprint-E" :generation "binding-F" :now 100})
                   {:version 2 :op :admission/chunk :args {:generation "generation-A" :chunk "text"}}])
    "read" (let [[chunk leave cancel reserve future-command] (nippy/thaw-from-file path)
                 initial {:entries {"generation-A" {:chunks []}
                                    "chunk-B" {:chunks ["untouched"]}}
                          :waiters {"waiter-C" "generation-A" "other" "generation-A"}
                          :waiter-expires {"waiter-C" 200 "other" 200}}
                 streamed (commands/apply-command initial chunk)
                 terminal (assoc-in streamed [:entries "generation-A" :terminal?] true)
                 left (commands/apply-command terminal leave)
                 cancelled (commands/apply-command
                            {:entries {"active" {:identity ["one"] :call-id "call-D" :phase :active}
                                       "queued" {:identity ["two"] :call-id "call-D" :phase :queued}
                                       "unrelated" {:identity ["three"] :call-id "other-call" :phase :queued}}
                             :identities {["one"] "active" ["two"] "queued" ["three"] "unrelated"}
                             :active #{"active"} :queue ["queued" "unrelated"]}
                            cancel)
                 binding (commands/apply-command nil reserve)]
             (assert (= ["Unsupported command version" {:type :bridge.commands/unsupported-version}]
                        (rejection #(commands/apply-command initial future-command))))
             (doseq [unwritable [nil {:chunks [] :terminal? true} {:chunks [] :cancelled? true}]]
               (let [before {:entries {"generation-A" unwritable}}]
                 (assert (= before (commands/apply-command before chunk)))))
             (assert (= 3600100 (get-in cancelled [:cancellations "call-D"])))
             (assert (true? (get-in cancelled [:entries "active" :cancelled?])))
             (assert (not (get-in cancelled [:entries "active" :terminal?])))
             (assert (= #{"active"} (:active cancelled)))
             (assert (true? (get-in cancelled [:entries "queued" :terminal?])))
             (assert (= ["unrelated"] (:queue cancelled)))
             (assert (not (get-in cancelled [:entries "unrelated" :cancelled?])))
             (assert (= cancelled (commands/apply-command cancelled cancel)))
             (assert (= {:fingerprint "fingerprint-E" :generation "binding-F" :expires-at 3600100} binding))
             (assert (= binding (commands/apply-command binding reserve)))
             (assert (= binding (commands/apply-command binding
                                                        (commands/command :replay/reserve
                                                                          {:fingerprint "different-fingerprint" :generation "different-binding" :now 101}))))
             (assert (= "replacement" (:generation
                                       (commands/apply-command binding
                                                               (commands/command :replay/reserve
                                                                                 {:fingerprint "new-fingerprint" :generation "replacement" :now 3600100})))))
             (assert (= ["chunk-B"] (get-in streamed [:entries "generation-A" :chunks])))
             (assert (= ["untouched"] (get-in streamed [:entries "chunk-B" :chunks])))
             (assert (= {"other" "generation-A"} (:waiters left)))
             (assert (= {"other" 200} (:waiter-expires left)))
             (assert (get-in left [:entries "generation-A"]))
             (let [last-left (commands/apply-command left
                                                     (commands/command :admission/leave
                                                                       {:generation "generation-A" :candidate "other"}))]
               (assert (nil? (get-in last-left [:entries "generation-A"]))))))
  (shutdown-agents))

(deftest persisted-commands-survive-cold-jvms
  (let [path (.toFile (Files/createTempFile "bridge-command-cold-" ".bin"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [writer (cold-process! "write" (.getAbsolutePath path) 0)]
        (is (zero? (:exit writer)) (pr-str writer))
        (when (zero? (:exit writer))
          (let [reader (cold-process! "read" (.getAbsolutePath path) 137)]
            (is (zero? (:exit reader)) (pr-str reader)))))
      (finally (io/delete-file path)))))

(deftest reject-unrecognized-or-malformed-data-without-payload-disclosure
  (let [valid (commands/command :admission/chunk {:generation "generation" :chunk "sensitive-payload"})]
    (is (= ["Unsupported command version" {:type :bridge.commands/unsupported-version}]
           (rejection #(commands/apply-command {} (assoc valid :version 2)))))
    (is (= ["Unsupported command operation" {:type :bridge.commands/unsupported-operation}]
           (rejection #(commands/command :unknown/op {:secret "sensitive-payload"}))))
    (doseq [invalid [nil "sensitive-payload" 42 [] '("sensitive-payload") (dissoc valid :version) (assoc valid :extra "sensitive-payload")
                     (assoc valid :args {})
                     (assoc valid :args {:generation "generation" :chunk identity})
                     (assoc valid :args {:generation "" :chunk "sensitive-payload"})
                     (assoc valid :args {:generation "generation" :chunk "text" :extra "sensitive-payload"})]]
      (is (= ["Malformed command" {:type :bridge.commands/invalid-command}]
             (rejection #(commands/apply-command {} invalid)))))
    (doseq [now [-1 1.5 Long/MAX_VALUE]]
      (is (= ["Malformed command" {:type :bridge.commands/invalid-command}]
             (rejection #(commands/command :admission/cancel {:call-id "sensitive-payload" :now now})))))))

(deftest command-discards-executable-container-metadata
  (let [args (with-meta {:generation "generation" :chunk "text"}
               {:capture (fn [] "never serialize me")})
        result (commands/command :admission/chunk args)]
    (is (nil? (meta result)))
    (is (nil? (meta (:args result))))
    (is (= ["text"] (get-in (commands/apply-command
                             {:entries {"generation" {:chunks []}}} result)
                            [:entries "generation" :chunks])))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'bridge.commands-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
