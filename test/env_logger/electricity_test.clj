(ns env-logger.electricity-test
  (:require [clojure.test :refer [deftest is testing]]
            [buddy.auth :refer [authenticated?]]
            [config.core :refer [env]]
            [java-time.api :refer [format]]
            [jsonista.core :as j]
            [env-logger.db :as db]
            [env-logger.db-test :refer [test-ds]]
            [env-logger.electricity :as e]))

(deftest electricity-data-test
  (testing "Electricity data fetch function"
    (with-redefs [db/postgres-ds test-ds]
      (with-redefs [authenticated? (fn [_] false)]
        (is (= 401 (:status (e/electricity-data {})))))
      (with-redefs [env {:show-elec-price false}
                    authenticated? (fn [_] true)]
        (is (= {"error" "not-enabled"}
               (j/read-value (:body (e/electricity-data {}))))))
      (with-redefs [authenticated? (fn [_] true)
                    format (fn [_ _] "2023-02-22")
                    db/get-elec-data-hour (fn [_ _ _]
                                            [{:start-time 123
                                              :price 10.0
                                              :consumption 0.5}])
                    db/get-elec-data-day (fn [_ _ _]
                                           [{:date "2023-09-04"
                                             :price 7.0
                                             :consumption 1.8}])
                    db/get-elec-consumption-interval-start (fn [_] "2023-02-21")
                    db/get-elec-price-interval-end (fn [_] "2023-09-05")
                    db/get-month-avg-elec-price (fn [_] "10.0")
                    db/get-month-elec-consumption (fn [_] "70.1")]
        (let [resp (e/electricity-data
                    {:params {"endDate" "2022-10-08"}})]
          (is (= 400 (:status resp)))
          (is (= "Missing parameter" (:body resp))))
        (is (= {"dates" {"current" {"start" "2022-10-08", "end" "2022-10-08"}}
                "data-hour" [{"consumption" 0.5, "start-time" 123, "price" 10.0}]
                "data-day" [{"consumption" 1.8, "date" "2023-09-04", "price" 7.0}]
                "month-price-avg" "10.0" "month-consumption" "70.1"
                "month-cost" 0.32 "interval-cost" 0.31}
               (j/read-value (:body (e/electricity-data
                                     {:params {"startDate" "2022-10-08"
                                               "endDate" "2022-10-08"}})))))
        (is (= {"dates" {"max" "2023-09-05",
                         "min" "2023-02-21",
                         "current" {"start" "2023-02-22"}}
                "data-hour" [{"consumption" 0.5, "start-time" 123, "price" 10.0}]
                "data-day" [{"consumption" 1.8, "date" "2023-09-04", "price" 7.0}]
                "month-price-avg" "10.0" "month-consumption" "70.1"
                "month-cost" 0.32 "interval-cost" 2.21}
               (j/read-value (:body (e/electricity-data
                                     {:params {}})))))))))

(deftest parse-consumption-data-file-test
  (testing "Electricity consumption data file parsing"
    (is (= {:error "no-data"} (e/parse-consumption-data-file
                               "test/env_logger/elec_consumption_short.csv")))
    (is (= {:error "invalid-format"}
           (e/parse-consumption-data-file
            "test/env_logger/elec_consumption_invalid.csv")))
    (let [data (e/parse-consumption-data-file "test/env_logger/elec_consumption_ok.csv")]
      (is (= 4 (count data)))
      (is (= java.sql.Timestamp (type (nth (first data) 0))))
      (is (= (float 0.12) (nth (first data) 1)))
      (is (= java.sql.Timestamp (type (nth (nth data 3) 0))))
      (is (= (float 0.15) (nth (nth data 3) 1))))))
