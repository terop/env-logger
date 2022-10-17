(ns env-logger.electricity-test
  (:require [clojure.test :refer [deftest is testing]]
            [config.core :refer [env]]
            [jsonista.core :as j]
            [env-logger
             [db :as db]
             [db-test :refer [test-ds]]
             [electricity :as e]]))

(deftest electricity-price-test
  (testing "Electricity price fetch function"
    (with-redefs [db/postgres-ds test-ds]
      (with-redefs [env {:show-elec-price false}]
        (is (= {"error" "not-enabled"}
               (j/read-value (:body (e/electricity-price {}))))))
      (with-redefs [db/get-elec-price (fn [_ _ _]
                                        [{:start-time 123
                                          :price 10.0}])]
        (let [resp (e/electricity-price
                    {:params {"endDate" "2022-10-08"}})]
          (is (= 400 (:status resp)))
          (is (= "Missing parameter" (:body resp))))
        (is (= [{"start-time" 123
                 "price" 10.0}]
               (j/read-value (:body (e/electricity-price
                                     {:params {"startDate" "2022-10-08"
                                               "endDate" "2022-10-08"}})))))
        (is (= [{"start-time" 123
                 "price" 10.0}]
               (j/read-value (:body (e/electricity-price
                                     {:params {}})))))))))
