(ns env-logger.handler-test
  (:require [clojure.test :refer :all]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.handler :as h]))

(deftest auth-code-check
  (testing "Authentication code value check"
    (with-redefs [get-conf-value (fn [_] "testvalue")]
      (is (false? (h/check-auth-code "notmatching")))
      (is (true? (h/check-auth-code "testvalue"))))))
