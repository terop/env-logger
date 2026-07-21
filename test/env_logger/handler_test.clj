(ns env-logger.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [config.core :refer [env]]
            [jsonista.core :as j]
            [terop.openid-connect-auth :refer [access-ok?]]
            [env-logger.db :as db]
            [env-logger.test-db :refer [test-ds]]
            [env-logger.electricity :as e]
            [env-logger.handler :as h]
            [env-logger.weather :as w]))

(deftest convert-epoch-ms->string-test
  (testing "Unix millisecond timestamp to string conversion"
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (is (= "4.8.2021 16:51:32"
             (h/convert-epoch-ms->string 1628085092000))))))

(deftest observation-insert-test
  (testing "Observation insert function"
    (with-redefs [db/postgres-ds test-ds]
      (with-redefs [w/fetch-all-weather-data (fn [] {})]
        (is (= 401 (:status (h/observation-insert {}))))
        (with-redefs [access-ok? (fn [_ _] true)]
          (is (= 400 (:status (h/observation-insert {}))))
          (with-redefs [db/test-db-connection (fn [_] false)]
            (is (= 500 (:status (h/observation-insert {})))))
          (let [request {:params
                         {"observation"
                          (j/write-value-as-string {:timestamp ""
                                                    :beacons ""
                                                    :co2 600
                                                    :insideLight 0
                                                    :insideTemperature 21
                                                    :outsideTemperature 0})}}]
            (with-redefs [h/handle-observation-insert (fn [_] true)]
              (is (= "OK" (:body (h/observation-insert request)))))
            (with-redefs [h/handle-observation-insert (fn [_] false)]
              (is (= 500 (:status (h/observation-insert request)))))))))))

(deftest rt-observation-insert-test
  (testing "RuuviTag observation insert function"
    (is (= 401 (:status (h/rd-observation-insert {}))))
    (with-redefs [access-ok? (fn [_ _] true)
                  db/postgres-ds test-ds]
      (with-redefs [db/test-db-connection (fn [_] false)]
        (is (= 500 (:status (h/rd-observation-insert {})))))
      (with-redefs [db/insert-ruuvi-device-observations (fn [_ _ _] true)]
        (is (= "OK" (:body (h/rd-observation-insert {})))))
      (with-redefs [db/insert-ruuvi-device-observations (fn [_ _ _] false)]
        (is (= 500 (:status (h/rd-observation-insert {}))))))))

(deftest tb-image-insert-test
  (testing "FMI Testbed image insert function"
    (is (= 401 (:status (h/tb-image-insert {}))))
    (with-redefs [access-ok? (fn [_ _] true)
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
    (is (= {"error" "Time zone ID has an invalid format"}
           (j/read-value (:body (h/time-data {:params {"timezone" ""}})))))
    (is (= {"error" "Cannot find time zone ID"}
           (j/read-value (:body (h/time-data {:params {"timezone" "Foo"}})))))
    (let [resp (j/read-value (:body (h/time-data
                                     {:params {"timezone" "UTC"}})))]
      (is (zero? (get resp "offset-hour"))))
    (with-redefs [db/get-tz-offset (fn [_] 3)]
      (let [resp (j/read-value (:body (h/time-data
                                       {:params {"timezone"
                                                 "Europe/Helsinki"}})))]
        (is (= 3 (get resp "offset-hour")))))))

(deftest display-interval-valid?-test
  (testing "Display interval validation"
    (is (h/display-interval-valid? nil nil 90))
    (is (h/display-interval-valid? "2020-01-01" nil 90))
    (is (h/display-interval-valid? "2020-01-01" "2020-03-30" 90))
    (is (not (h/display-interval-valid? "2020-01-01" "2020-03-31" 90)))
    (is (not (h/display-interval-valid? "2020-04-01" "2020-01-01" 90)))))

(deftest get-display-data-date-range-test
  (testing "get-display-data date range limits"
    (is (= 401 (:status (h/get-display-data {}))))
    (with-redefs [access-ok? (fn [_ _] true)]
      (is (= 400 (:status (h/get-display-data
                           {:params {"startDate" "2020-01-01"
                                     "endDate" "2020-03-31"}}))))
      (let [resp (h/get-display-data
                  {:params {"startDate" "2020-01-01"
                            "endDate" "2020-03-31"}})
            body (j/read-value (:body resp) j/keyword-keys-object-mapper)]
        (is (= "date-range-too-large" (:error body)))
        (is (= 90 (:max-days body))))
      (with-redefs [h/get-plot-page-data (fn [_] {:obs-data {}
                                                  :display-resolution "hourly"})
                    w/get-weather-data (fn [] [])]
        (is (= 200 (:status (h/get-display-data
                             {:params {"startDate" "2020-01-01"
                                       "endDate" "2020-03-30"}}))))))))

(deftest get-plot-page-data-resolution-test
  (testing "get-plot-page-data includes display-resolution for long ranges"
    (with-redefs [db/postgres-ds test-ds
                  db/get-obs-date-interval (fn [_] {:start "2020-01-01"
                                                    :end "2020-03-01"})
                  env (assoc env :display-bucket-target-points 1500
                             :initial-show-days 30)]
      (let [result (h/get-plot-page-data {:params {"startDate" "2020-01-01"
                                                   "endDate" "2020-02-15"}})]
        (is (contains? result :display-resolution))
        (is (not= "10min" (:display-resolution result))))))
  (testing "get-plot-page-data omits averaging label for short ranges"
    (with-redefs [db/postgres-ds test-ds
                  db/get-obs-date-interval (fn [_] {:start "2020-01-01"
                                                    :end "2020-03-01"})
                  env (assoc env :display-bucket-target-points 1500
                             :initial-show-days 2)]
      (let [result (h/get-plot-page-data {:params {}})]
        (is (nil? (:display-resolution result))))))
  (testing "get-plot-page-data shows 10min label when observations are bucketed"
    (with-redefs [db/postgres-ds test-ds
                  db/get-obs-date-interval (fn [_] {:start "2020-01-01"
                                                    :end "2020-03-01"})
                  env (assoc env :display-bucket-target-points 1500
                             :initial-show-days 7)]
      (let [result (h/get-plot-page-data {:params {}})]
        (is (= "10min" (:display-resolution result)))))))

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
