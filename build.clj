(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]))

(def lib 'com.github.terop/env-logger)
(def version "0.2.11-SNAPSHOT")
(def main 'env-logger.handler)

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn clean "Do cleanup." [opts]
  (bb/clean opts))

(defn ci "Run the CI pipeline of tests." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (bb/run-tests)
      (bb/clean)))

(defn uberjar "Build uberjar." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (bb/clean)
      (bb/uber)))
