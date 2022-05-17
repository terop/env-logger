(ns env-logger.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [env-logger.db :as db]
            [env-logger.handler :as h]))

(deftest convert-epoch-ms-to-string-test
  (testing "Unix millisecond timestamp to string conversion"
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (is (= "4.8.2021 16:51:32"
             (h/convert-epoch-ms-to-string 1628085092000))))))
