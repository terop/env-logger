(ns env-logger.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [java-time :as t]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [env-logger.handler :as h]
            [env-logger.db-test :refer [get-image-name]]))

(deftest auth-code-check
  (testing "Authentication code value check"
    (with-redefs [get-conf-value (fn [_] "testvalue")]
      (is (false? (h/check-auth-code "notmatching")))
      (is (true? (h/check-auth-code "testvalue"))))))

(deftest yc-image-validity-check-test
  (testing "Yardcam image name validity"
    (is (false? (h/yc-image-validity-check nil)))
    (is (false? (h/yc-image-validity-check "test.jpg")))
    (is (true? (h/yc-image-validity-check (get-image-name "yardcam"))))
    (is (true? (h/yc-image-validity-check
                (get-image-name "yardcam"
                                (t/minutes (get-conf-value
                                            :image-max-time-diff))))))
    (is (false? (h/yc-image-validity-check
                 (get-image-name "yardcam"
                                 (t/minutes (inc (get-conf-value
                                                  :image-max-time-diff)))))))))

(deftest convert-epoch-ms-to-string-test
  (testing "Unix millisecond timestamp to string conversion"
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (is (= "4.8.2021 16:51:32"
             (h/convert-epoch-ms-to-string 1628085092000))))))
