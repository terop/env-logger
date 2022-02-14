(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]
            [badigeon.javac :as javac]))

(def lib 'com.github.terop/env-logger)
(def version "0.2.11-SNAPSHOT")
(def main 'env-logger.handler)

(defn clean "Do cleanup." [opts]
  (bb/clean opts))

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn build-java "Build Java sources." [_]
  (javac/javac "src/java"))

(defn ci "Run the CI pipeline of tests." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (build-java)
      (bb/run-tests)))

(defn uberjar "Build uberjar." [opts]
  (-> opts
      (bb/clean)
      (build-java)
      (assoc :lib lib :version version :main main)
      (bb/uber)))
