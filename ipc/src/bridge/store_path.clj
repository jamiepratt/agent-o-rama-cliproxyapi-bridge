(ns bridge.store-path
  "Source-loaded boundary to AOR's runtime-generated store protocol interfaces.
  Keep this namespace out of AOT: public store macros emit direct interface calls."
  (:require [com.rpl.agent-o-rama.store :as store]))

(defn transform! [path state key]
  (store/pstate-transform! path state key))

(defn select-one [path state]
  (store/pstate-select-one path state))
