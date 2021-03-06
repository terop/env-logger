(ns env-logger.handler-test
  (:require [clojure.test :refer :all]
            [java-time :as t]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.handler :as h]
            [env-logger.db-test :refer [get-yc-image-name]]))

(deftest auth-code-check
  (testing "Authentication code value check"
    (with-redefs [get-conf-value (fn [_] "testvalue")]
      (is (false? (h/check-auth-code "notmatching")))
      (is (true? (h/check-auth-code "testvalue"))))))

(deftest yc-image-validity-check-test
  (testing "Yardcam image name validity"
    (is (false? (h/yc-image-validity-check nil)))
    (is (false? (h/yc-image-validity-check "test.jpg")))
    (is (true? (h/yc-image-validity-check (get-yc-image-name))))
    (is (true? (h/yc-image-validity-check
                (get-yc-image-name (t/minutes (get-conf-value
                                               :yc-max-time-diff))))))
    (is (false? (h/yc-image-validity-check
                 (get-yc-image-name (t/minutes (inc (get-conf-value
                                                     :yc-max-time-diff)))))))))
