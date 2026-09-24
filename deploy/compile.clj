;; Load dependencies without *compile-files* first: do not recompile Rama/AOR.
;; deployed is excluded: loading it reads /etc/bridge/module.edn.
;; store-path stays source-loaded to resolve AOR runtime protocol interfaces.
(def application-namespaces
  '[bridge.locks bridge.metrics bridge.admission bridge.replay bridge.module
    bridge.observability bridge.runtime bridge.commands])
(doseq [sym application-namespaces] (require sym))
(binding [*compile-path* (first *command-line-args*)]
  (doseq [sym application-namespaces]
    (println :compile sym)
    (compile sym)))
;; Bound the output to application bytecode, even if a future require starts
;; compiling transitively. Remove only sources with a verified compiled loader.
(let [output (java.io.File. (first *command-line-args*))
      classes (filter #(.endsWith (.getName %) ".class") (file-seq output))]
  (doseq [file classes]
    (assert (.startsWith (.toPath file) (.toPath (java.io.File. output "bridge")))
            (str "Unexpected dependency bytecode: " file)))
  (doseq [sym application-namespaces]
    (let [base (.replace (str sym) \. \/)]
      (assert (.isFile (java.io.File. output (str base "__init.class"))))
      (let [source (java.io.File. output (str base ".clj"))]
        (assert (.delete source) (str "Could not remove compiled source: " source)))))
  (doseq [base ["bridge/store_path" "bridge/deployed"]]
    (assert (.isFile (java.io.File. output (str base ".clj"))))
    (assert (not (.exists (java.io.File. output (str base "__init.class"))))))
  (println :application-class-count (count classes)))
(shutdown-agents)
