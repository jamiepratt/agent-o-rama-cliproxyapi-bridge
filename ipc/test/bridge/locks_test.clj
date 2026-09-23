(ns bridge.locks-test
  (:require [clojure.test :refer [deftest is]]
            [bridge.locks :as locks])
  (:import [java.io BufferedReader InputStreamReader]
           [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(defn child [directory generation wait?]
  (.start
   (ProcessBuilder.
    ^java.util.List
    ["python3" "-c"
     (str "import fcntl,sys,signal\nf=open(sys.argv[1],'a')\ntry:\n fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\nexcept BlockingIOError:\n print('blocked',flush=True)\n sys.exit(0)\nprint('locked',flush=True)\n"
          (when wait? "signal.pause()\n"))
     (str directory "/" generation ".lock")])))

(defn line-within [process]
  (let [read-line (future (.readLine (BufferedReader. (InputStreamReader. (.getInputStream process)))))]
    (deref read-line 5000 :timeout)))

(deftest live-fences-survive-local-probes-and-release-on-process-death
  (let [{:keys [directory]} (locks/prepare-directory!
                             (str (Files/createTempDirectory "bridge-lock-test-" (make-array FileAttribute 0))))
        generation (str (UUID/randomUUID))]
    (try
      (with-open [owned (locks/try-acquire! directory generation)]
        (is (some? owned))
        (is (nil? (locks/try-acquire! directory generation)))
        (let [probe (child directory generation false)]
          (try
            ;; Closing an extra JVM descriptor must not silently drop the live fence.
            (is (= "blocked" (line-within probe)))
            (is (.waitFor probe 5 TimeUnit/SECONDS))
            (finally (.destroyForcibly probe)))))
      (let [owner (child directory generation true)]
        (try
          (is (= "locked" (line-within owner)))
          (is (nil? (locks/try-acquire! directory generation)))
          (.destroyForcibly owner)
          (is (.waitFor owner 5 TimeUnit/SECONDS))
          (with-open [recovered (locks/try-acquire! directory generation)]
            (is (some? recovered)))
          (finally (.destroyForcibly owner))))
      (finally
        (locks/remove-retired! directory generation)
        (doseq [name ["domain" "directory.lock"]]
          (Files/deleteIfExists (.resolve (Paths/get directory (make-array String 0)) name)))
        (Files/deleteIfExists (Paths/get directory (make-array String 0)))))))
