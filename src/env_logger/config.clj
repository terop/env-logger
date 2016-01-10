(ns env-logger.config
  (:require [clojure.edn :as edn]))

(defn load-config
  "Given a filename, load and return a config file"
  [filename]
  (edn/read-string (slurp filename)))

(defn get-conf-value
  "Return a key value from the configuration"
  [property config-key]
  (config-key (property (load-config
                         (clojure.java.io/resource "config.edn")))))
