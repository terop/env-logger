(ns env-logger.config-test
  (:require [clojure.test :refer :all]
            [env-logger.config :refer [get-conf-value]]))

;; Config reading test
(deftest read-configuration-value
  (testing "Configuration value reading"
    (is (nil? (get-conf-value :foo)))
    (is (false? (get-conf-value :in-production)))
    (is (true? (get-conf-value :correction :enabled)))))
