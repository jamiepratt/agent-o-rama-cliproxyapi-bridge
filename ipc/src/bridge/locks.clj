(ns bridge.locks
  "Single-host process-death fences. These locks do not implement admission limits."
  (:import [java.io Closeable]
           [java.nio.channels FileChannel]
           [java.nio.file Files Paths LinkOption StandardOpenOption OpenOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]
           [java.util UUID]))

;; POSIX closing *any* descriptor for an inode can drop this process's locks.
;; Keep exactly one channel per canonical path, including unsuccessful probes.
(defonce channels (atom #{}))
(def no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def file-attributes (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rw-------"))]))
(def directory-attributes (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))]))

(defn try-acquire!
  "Return a Closeable fence, or nil while any process owns this UUID."
  [directory generation]
  (let [path (.resolve (Paths/get ^String directory (make-array String 0)) (str generation ".lock"))
        key (str path)]
    (locking channels
      (when-not (contains? @channels key)
        (let [channel (FileChannel/open path
                                        #{StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS}
                                        file-attributes)]
          (try
            (if-let [fence (.tryLock channel)]
              (do
                (swap! channels conj key)
                (let [closed? (atom false)]
                  (reify Closeable
                    (close [_]
                      (locking channels
                        (when (compare-and-set! closed? false true)
                          (try (.release fence)
                               (finally (.close channel) (swap! channels disj key)))))))))
              (do (.close channel) nil))
            (catch Throwable error (.close channel) (throw error))))))))

(defn remove-retired! [directory generation]
  ;; Only use after durable terminal state or for an unregistered candidate.
  ;; UUIDs are never reused for dispatch; stale recovery probes cannot resurrect one.
  (Files/deleteIfExists (.resolve (Paths/get ^String directory (make-array String 0)) (str generation ".lock"))))

(defn prepare-directory! [directory]
  (let [path (Paths/get ^String directory (make-array String 0))]
    (when (Files/isSymbolicLink path)
      (throw (ex-info "Lock directory must not be a symbolic link" {:type ::invalid-directory})))
    (Files/createDirectories path directory-attributes)
    (when (or (not= (PosixFilePermissions/fromString "rwx------") (Files/getPosixFilePermissions path no-links))
              (#{"nfs" "smbfs" "cifs"} (.type (Files/getFileStore path))))
      (throw (ex-info "Lock directory requires private local POSIX storage" {:type ::invalid-directory})))
    (let [directory (str (.toRealPath path (make-array LinkOption 0)))
          marker (.resolve (Paths/get directory (make-array String 0)) "domain")]
      (loop [remaining 250]
        (if-let [fence (try-acquire! directory "directory")]
          (with-open [_ fence]
            (when-not (Files/exists marker no-links)
              (Files/createFile marker file-attributes)
              (Files/writeString marker (str (UUID/randomUUID)) (make-array OpenOption 0)))
            (when (or (Files/isSymbolicLink marker)
                      (not= (PosixFilePermissions/fromString "rw-------") (Files/getPosixFilePermissions marker no-links)))
              (throw (ex-info "Lock directory marker must be private" {:type ::invalid-directory})))
            {:directory directory :domain [directory (str (UUID/fromString (Files/readString marker)))]})
          (if (pos? remaining)
            (do (Thread/sleep 20) (recur (dec remaining)))
            (throw (ex-info "Lock directory initialization is busy" {:type ::invalid-directory}))))))))
