(ns env-logger.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as j]
            [env-logger
             [authentication :as auth]
             [db :as db]
             [handler :as h]
             [weather :as w]]))

(deftest convert-epoch-ms-to-string-test
  (testing "Unix millisecond timestamp to string conversion"
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (is (= "4.8.2021 16:51:32"
             (h/convert-epoch-ms-to-string 1628085092000))))))

(deftest observation-insert-test
  (testing "Observation insert function"
    (with-redefs [w/fetch-all-weather-data (fn [] {})]
      (is (= 401 (:status (h/observation-insert {}))))
      (with-redefs [auth/check-auth-code (fn [_] true)]
        (is (= 400 (:status (h/observation-insert {}))))
        (with-redefs [db/test-db-connection (fn [_] false)]
          (is (= 500 (:status (h/observation-insert {})))))
        (let [request {:params
                       {"obs-string"
                        (j/write-value-as-string {:timestamp ""
                                                  :insideLight 0
                                                  :beacons ""
                                                  :outsideTemperature 0})}}]
          (with-redefs [h/handle-observation-insert (fn [_] true)]
            (is (= "OK" (:body (h/observation-insert request)))))
          (with-redefs [h/handle-observation-insert (fn [_] false)]
            (is (= 500 (:status (h/observation-insert request))))))))))

(deftest rt-observation-insert-test
  (testing "RuuviTag observation insert function"
    (is (= 401 (:status (h/rt-observation-insert {}))))
    (with-redefs [auth/check-auth-code (fn [_] true)]
      (with-redefs [db/test-db-connection (fn [_] false)]
        (is (= 500 (:status (h/rt-observation-insert {})))))
      (with-redefs [db/insert-ruuvitag-observation (fn [_ _] 1)]
        (is (= "OK" (:body (h/rt-observation-insert {})))))
      (with-redefs [db/insert-ruuvitag-observation (fn [_ _] 0)]
        (is (= 500 (:status (h/rt-observation-insert {}))))))))

(deftest tb-image-insert-test
  (testing "FMI Testbed image insert function"
    (is (= 401 (:status (h/tb-image-insert {}))))
    (with-redefs [auth/check-auth-code (fn [_] true)]
      (with-redefs [db/test-db-connection (fn [_] false)]
        (is (= 500 (:status (h/tb-image-insert {})))))
      (is (= 400 (:status (h/tb-image-insert {:params {"name" "foo.png"}}))))
      (let [request {:params {"name"
                              "testbed-2022-08-02T10:16+0300.png"}}]
        (with-redefs [db/insert-tb-image-name (fn [_ _ _] true)]
          (is (= "OK" (:body (h/tb-image-insert request)))))
        (with-redefs [db/insert-tb-image-name (fn [_ _ _] false)]
          (is (= 500 (:status (h/tb-image-insert request)))))))))
