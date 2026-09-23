(ns bridge.deployed
  "Rama CLI entrypoint: --module bridge.deployed/ProxyModule."
  (:require [bridge.module :as module]
            [bridge.runtime :as runtime]))

;; Only non-secret values and the private bearer pathname enter the serialized module.
(def ProxyModule (module/proxy-module (runtime/read-config)))
