(ns env-logger.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as j]
            [env-logger
             [authentication :as auth]
             [db :as db]
             [db-test :refer [test-ds]]
             [electricity :as e]
             [handler :as h]
             [weather :as w]]))

(deftest convert-epoch-ms-to-string-test
  (testing "Unix millisecond timestamp to string conversion"
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (is (= "4.8.2021 16:51:32"
             (h/convert-epoch-ms-to-string 1628085092000))))))

(deftest observation-insert-test
  (testing "Observation insert function"
    (with-redefs [db/postgres-ds test-ds]
      (with-redefs [w/fetch-all-weather-data (fn [] {})]
        (is (= 401 (:status (h/observation-insert {}))))
        (with-redefs [auth/check-auth-code (fn [_] true)]
          (is (= 400 (:status (h/observation-insert {}))))
          (with-redefs [db/test-db-connection (fn [_] false)]
            (is (= 500 (:status (h/observation-insert {})))))
          (let [request {:params
                         {"observation"
                          (j/write-value-as-string {:timestamp ""
                                                    :insideLight 0
                                                    :beacons ""
                                                    :outsideTemperature 0})}}]
            (with-redefs [h/handle-observation-insert (fn [_] true)]
              (is (= "OK" (:body (h/observation-insert request)))))
            (with-redefs [h/handle-observation-insert (fn [_] false)]
              (is (= 500 (:status (h/observation-insert request)))))))))))

(deftest rt-observation-insert-test
  (testing "RuuviTag observation insert function"
    (is (= 401 (:status (h/rt-observation-insert {}))))
    (with-redefs [auth/check-auth-code (fn [_] true)
                  db/postgres-ds test-ds]
      (with-redefs [db/test-db-connection (fn [_] false)]
        (is (= 500 (:status (h/rt-observation-insert {})))))
      (with-redefs [db/insert-ruuvitag-observation (fn [_ _] 1)]
        (is (= "OK" (:body (h/rt-observation-insert {})))))
      (with-redefs [db/insert-ruuvitag-observation (fn [_ _] 0)]
        (is (= 500 (:status (h/rt-observation-insert {}))))))))

(deftest tb-image-insert-test
  (testing "FMI Testbed image insert function"
    (is (= 401 (:status (h/tb-image-insert {}))))
    (with-redefs [auth/check-auth-code (fn [_] true)
                  db/postgres-ds test-ds]
      (with-redefs [db/test-db-connection (fn [_] false)]
        (is (= 500 (:status (h/tb-image-insert {})))))
      (is (= 400 (:status (h/tb-image-insert {:params {"name" "foo.png"}}))))
      (let [request {:params {"name"
                              "testbed-2022-08-02T10:16+0300.png"}}]
        (with-redefs [db/insert-tb-image-name (fn [_ _ _] true)]
          (is (= "OK" (:body (h/tb-image-insert request)))))
        (with-redefs [db/insert-tb-image-name (fn [_ _ _] false)]
          (is (= 500 (:status (h/tb-image-insert request)))))))))

(deftest time-data-test
  (testing "Time data function"
    (is (= {"error" "Unspecified error"}
           (j/read-value (:body (h/time-data {:params {}})))))
    (is (= {"error" "Timezone ID has an invalid format"}
           (j/read-value (:body (h/time-data {:params {"timezone" ""}})))))
    (is (= {"error" "Cannot find timezone ID"}
           (j/read-value (:body (h/time-data {:params {"timezone" "Foo"}})))))
    (let [resp (j/read-value (:body (h/time-data
                                     {:params {"timezone" "UTC"}})))]
      (is (zero? (get resp "offset-hour"))))
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (let [resp (j/read-value (:body (h/time-data
                                       {:params {"timezone"
                                                 "Europe/Helsinki"}})))]
        (is (= 3 (get resp "offset-hour")))))))

(deftest elec-consumption-data-upload-test
  (testing "Electricity consumption data upload"
    (is (= {:status "error"
            :cause "invalid-filename"}
           (h/elec-consumption-data-upload {:params {"consumption-file"
                                                     {:filename "notfound.txt"}}})))
    (let [request {:params {"consumption-file"
                            {:filename "consumption.csv"}}}]
      (with-redefs [e/parse-consumption-data-file (fn [_] {:error "myerror"})]
        (is (= {:status "error"
                :cause "myerror"}
               (h/elec-consumption-data-upload request))))
      (with-redefs [e/parse-consumption-data-file (fn [_] {})
                    db/postgres-ds test-ds
                    db/insert-elec-consumption-data (fn [_ _] false)]
        (is (= {:status "error"}
               (h/elec-consumption-data-upload request))))
      (with-redefs [e/parse-consumption-data-file (fn [_] {})
                    db/postgres-ds test-ds
                    db/insert-elec-consumption-data (fn [_ _] true)]
        (is (= {:status "success"}
               (h/elec-consumption-data-upload request)))))))
