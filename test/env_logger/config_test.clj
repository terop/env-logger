(ns env-logger.config-test
  (:require [clojure.test :refer :all]
            [env-logger.config :refer :all]))

(deftest read-configuration-value
  (testing "Basic configuration value reading"
    (is (nil? (get-conf-value :foo :use-sample true)))
    (is (true? (get-conf-value :in-production :use-sample true)))))

(deftest read-database-conf-value
  (testing "Database configuration value reading"
    (is (= "db_name" (db-conf :name :use-sample true)))
    (is (= "foobar" (db-conf :username :use-sample true)))
    (is (nil? (db-conf :not-found :use-sample true)))))
