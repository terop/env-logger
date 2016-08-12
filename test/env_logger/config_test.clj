(ns env-logger.config-test
  (:require [clojure.test :refer :all]
            [env-logger.config :refer [get-conf-value]]))

(deftest read-configuration-value
  (testing "Basic configuration value reading"
    (is (nil? (get-conf-value :foo :use-sample true)))
    (is (true? (get-conf-value :in-production :use-sample true)))
    (is (= 9 (get-conf-value :correction :k :offset :use-sample true)))
    (is (false? (get-conf-value :correction :k :enabled :use-sample true)))))
