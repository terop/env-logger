(ns env-logger.electricity-test
  (:require [clojure.test :refer [deftest is testing]]
            [buddy.auth :refer [authenticated?]]
            [config.core :refer [env]]
            [java-time.api :refer [format]]
            [jsonista.core :as j]
            [env-logger
             [db :as db]
             [db-test :refer [test-ds]]
             [electricity :as e]]))

(deftest electricity-price-test
  (testing "Electricity price fetch function"
    (with-redefs [db/postgres-ds test-ds]
      (with-redefs [authenticated? (fn [_] false)]
        (is (= 401 (:status (e/electricity-data {})))))
      (with-redefs [env {:show-elec-price false}
                    authenticated? (fn [_] true)]
        (is (= {"error" "not-enabled"}
               (j/read-value (:body (e/electricity-data {}))))))
      (with-redefs [authenticated? (fn [_] true)
                    format (fn [_ _] "2023-02-22")
                    db/get-elec-data (fn [_ _ _]
                                       [{:start-time 123
                                         :price 10.0
                                         :usage 0.5}])
                    db/get-elec-usage-interval-start (fn [_] "2023-02-21")]
        (let [resp (e/electricity-data
                    {:params {"endDate" "2022-10-08"}})]
          (is (= 400 (:status resp)))
          (is (= "Missing parameter" (:body resp))))
        (is (= {"dates" {"current" {"start" "2022-10-08", "end" "2022-10-08"}}
                "data" [{"usage" 0.5, "start-time" 123, "price" 10.0}]}
               (j/read-value (:body (e/electricity-data
                                     {:params {"startDate" "2022-10-08"
                                               "endDate" "2022-10-08"}})))))
        (is (= {"dates" {"min" "2023-02-21", "current" {"start" "2023-02-22"}}
                "data" [{"usage" 0.5, "start-time" 123, "price" 10.0}]}
               (j/read-value (:body (e/electricity-data
                                     {:params {}})))))))))

(deftest parse-usage-data-file-test
  (testing "Electricity usage data file parsing"
    (is (= {:error "no-data"} (e/parse-usage-data-file
                               "test/env_logger/elec_usage_short.csv")))
    (is (= {:error "invalid-format"}
           (e/parse-usage-data-file
            "test/env_logger/elec_usage_invalid.csv")))
    (let [data (e/parse-usage-data-file "test/env_logger/elec_usage_ok.csv")]
      (is (= 4 (count data)))
      (is (= java.sql.Timestamp (type (nth (first data) 0))))
      (is (= (float 0.12) (nth (first data) 1)))
      (is (= java.sql.Timestamp (type (nth (nth data 3) 0))))
      (is (= (float 0.15) (nth (nth data 3) 1))))))
