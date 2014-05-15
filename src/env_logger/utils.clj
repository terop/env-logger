(ns env-logger.utils
	(:require [clojure.edn :as edn]))

(defn load-config
  "Given a filename, load and return a config file"
  [filename]
  (edn/read-string (slurp filename)))
