(ns env-logger.db-test
  (:require [clj-time.core :as t]
            [clj-time.local :as l]
            [clj-time.format :as f]
            [clj-time.jdbc]
            [clojure.java.jdbc :as j]
            [clojure.test :refer :all]
            [clj-ldap.client :as ldap]
            [env-logger.config :refer [db-conf get-conf-value]]
            [env-logger.db :refer :all])
  (:import org.joda.time.DateTime
           java.util.concurrent.TimeUnit))

(let [db-host (get (System/getenv)
                   "POSTGRESQL_DB_HOST"
                   (db-conf :host))
      db-port (get (System/getenv)
                   "POSTGRESQL_DB_PORT"
                   (db-conf :port))
      db-name "env_logger_test"
      db-user (get (System/getenv)
                   "POSTGRESQL_DB_USERNAME"
                   (db-conf :username))
      db-password (get (System/getenv)
                       "POSTGRESQL_DB_PASSWORD"
                       (db-conf :password))]
  (def test-postgres {:classname "org.postgresql.Driver"
                      :subprotocol "postgresql"
                      :subname (format "//%s:%s/%s"
                                       db-host db-port db-name)
                      :user db-user
                      :password db-password}))

;; Current datetime used in tests
(def current-dt (t/now))
(def formatter :date-hour-minute-second)

(defn clean-test-database
  "Cleans the test database before and after running tests."
  [test-fn]
  (j/execute! test-postgres "DELETE FROM observations")
  (j/insert! test-postgres
             :observations
             {:recorded (l/to-local-date-time
                         (t/minus current-dt
                                  (t/days 4)))
              :brightness 5
              :temperature 20})
  (let [user-id (:user_id (first (j/insert! test-postgres
                                            :users
                                            {:username "test-user"
                                             :pw_hash "myhash"})))]
    (j/insert! test-postgres
               :yubikeys
               {:user_id user-id
                :yubikey_id "mykeyid"}))
  (test-fn)
  (j/execute! test-postgres "DELETE FROM observations")
  (j/execute! test-postgres "DELETE FROM users")
  (j/execute! test-postgres "DELETE FROM yardcam_images"))

;; Fixture run at the start and end of tests
(use-fixtures :once clean-test-database)

(deftest insert-observations
  (testing "Observation insertion"
    (is (true? (insert-observation test-postgres
                                   {:timestamp
                                    (str (l/to-local-date-time current-dt))
                                    :inside_light 0
                                    :inside_temp 20
                                    :beacons [{:rssi -68,
                                               :mac "7C:EC:79:3F:BE:97"}]
                                    :weather-data {:date
                                                   (str (l/to-local-date-time
                                                         current-dt))
                                                   :temperature 20
                                                   :cloudiness 2}
                                    :testbed-image (byte-array
                                                    (map byte "ascii"))})))
    (is (true? (insert-observation test-postgres
                                   {:timestamp
                                    (str (l/to-local-date-time current-dt))
                                    :inside_light 0
                                    :inside_temp 20
                                    :beacons [{:rssi -68,
                                               :mac "7C:EC:79:3F:BE:01"}]
                                    :weather-data {}
                                    :testbed_image nil})))
    (is (false? (insert-observation test-postgres {})))))

(deftest date-formatting
  (testing "Date formatting function"
    (is (= (l/format-local-time current-dt formatter)
           (format-datetime (l/to-local-date-time current-dt)
                            :date-hour-minute-second)))))

(deftest n-days-observations
  (testing "Selecting observations from N days"
    (is (= {:brightness 0
            :recorded (l/format-local-time current-dt formatter)
            :temperature 14.0
            :cloudiness 2
            :o_temperature 20.0
            :yc_image_name "testimage.jpg"
            :id (first (j/query test-postgres
                                "SELECT MIN(id) + 1 AS id FROM observations"
                                {:row-fn #(:id %)}))
            :beacons '({:name "7C:EC:79:3F:BE:97", :rssi -68})}
           (first (get-obs-days test-postgres 3))))))

(deftest obs-interval-select
  (testing "Select observations between one or two dates"
    (let [formatter (f/formatter "d.M.y")]
      (is (= 3 (count (get-obs-interval test-postgres nil nil))))
      (is (= 2 (count (get-obs-interval
                       test-postgres
                       (f/unparse formatter
                                  (t/minus current-dt
                                           (t/days 1)))
                       nil))))
      (is (= 1 (count (get-obs-interval
                       test-postgres
                       nil
                       (f/unparse formatter
                                  (t/minus current-dt
                                           (t/days 2)))))))
      (is (= 3 (count (get-obs-interval
                       test-postgres
                       (f/unparse formatter
                                  (t/minus current-dt
                                           (t/days 6)))
                       (f/unparse formatter
                                  current-dt)))))
      (is (zero? (count (get-obs-interval
                         test-postgres
                         (f/unparse formatter
                                    (t/minus current-dt
                                             (t/days 11)))
                         (f/unparse formatter
                                    (t/minus current-dt
                                             (t/days 10)))))))
      (is (zero? (count (get-obs-interval test-postgres "foobar" nil))))
      (is (zero? (count (get-obs-interval test-postgres nil "foobar"))))
      (is (zero? (count (get-obs-interval test-postgres "bar" "foo")))))))

(deftest get-observations-tests
  (testing "Observation querying with arbitrary WHERE clause and LIMIT"
    (is (= 1 (count (get-observations test-postgres
                                      :where [:= :o.temperature 20]))))
    (is (= 1 (count (get-observations test-postgres
                                      :limit 1))))
    (is (zero? (count (get-observations test-postgres
                                        :limit 0))))))

(deftest start-and-end-date-query
  (testing "Selecting start and end dates of all observations"
    (let [formatter (f/formatter "d.M.y")]
      (is (= (f/unparse formatter (t/minus current-dt
                                           (t/days 4)))
             (:start (get-obs-start-and-end test-postgres))))
      (is (= (f/unparse formatter current-dt)
             (:end (get-obs-start-and-end test-postgres)))))))

(deftest date-validation
  (testing "Tests for date validation"
    (is (true? (validate-date nil)))
    (is (false? (validate-date "foobar")))
    (is (false? (validate-date "1.12.201")))
    (is (true? (validate-date "1.12.2016")))
    (is (true? (validate-date "01.12.2016")))))

(deftest date-to-datetime
  (testing "Testing date to datetime conversion"
    (let [formatter (f/formatter "d.M.y H:m:s")]
      (is (= (f/parse formatter "1.12.2016 00:00:00")
             (make-date-dt "1.12.2016" "start")))
      (is (= (f/parse formatter "1.12.2016 23:59:59")
             (make-date-dt "1.12.2016" "end"))))))

(deftest weather-obs-interval-select
  (testing "Select weather observations between one or two dates"
    (let [formatter (f/formatter "d.M.y")]
      (is (= 1 (count (get-weather-obs-interval test-postgres
                                                nil
                                                nil))))
      (is (= 1 (count (get-weather-obs-interval
                       test-postgres
                       (f/unparse formatter
                                  (t/minus current-dt
                                           (t/days 1)))
                       nil))))
      (is (zero? (count (get-weather-obs-interval
                         test-postgres
                         nil
                         (f/unparse formatter
                                    (t/minus current-dt
                                             (t/days 2)))))))
      (is (zero? (count (get-weather-obs-interval
                         test-postgres
                         (f/unparse formatter
                                    (t/minus current-dt
                                             (t/days 5)))
                         (f/unparse formatter
                                    (t/minus current-dt
                                             (t/days 3))))))))))

(deftest weather-days-observations
  (testing "Selecting weather observations from N days"
    (is (= {:time (l/format-local-time current-dt formatter)
            :cloudiness 2
            :o_temperature 20.0
            :yc_image_name "testimage.jpg"
            :id (first (j/query test-postgres
                                "SELECT MIN(id) + 1 AS id FROM observations"
                                {:row-fn #(:id %)}))}
           (first (get-weather-obs-days test-postgres 1))))))

(deftest weather-observation-select
  (testing "Selecting weather observations with an arbitrary WHERE clause"
    (is (zero? (count (get-weather-observations test-postgres
                                                :where [:= 1 0]))))))

(deftest yubikey-id-query
  (testing "Querying for a user's Yubikey ID"
    (is (nil? (seq (get-yubikey-id test-postgres "notfound"))))
    (is (= (set ["mykeyid"]) (get-yubikey-id test-postgres "test-user")))))

(deftest user-data-query
  (testing "Querying for a user's password hash and Yubikey ID"
    (is (nil? (get-user-data test-postgres "notfound")))
    (is (= {:pw-hash "myhash"
            :yubikey-ids (set ["mykeyid"])}
           (get-user-data test-postgres "test-user")))))

(deftest password-from-ldap-query
  (testing "Searching for a user's password from LDAP"
    (with-redefs [ldap/connect (fn [options] nil)
                  ldap/get (fn [con dn fields] nil)]
      (is (nil? (get-password-from-ldap "notfound"))))
    (with-redefs [ldap/connect (fn [options] nil)
                  ldap/get (fn [con dn fields] {:userPassword "foobar"})]
      (is (= "foobar" (get-password-from-ldap "test-user"))))))

(deftest yc-image-name-storage
  (testing "Storing of the latest Yardcam image name"
    (j/insert! test-postgres
               :yardcam_images
               {:image_name "testimage.jpg"})
    (j/insert! test-postgres
               :yardcam_images
               {:image_name "testimage2.jpg"})
    (is (= 2 (count (j/query test-postgres
                             "SELECT image_id FROM yardcam_images"))))
    (is (true? (insert-yc-image-name test-postgres "testimage.jpg")))
    (is (= 1 (count (j/query test-postgres
                             "SELECT image_id FROM yardcam_images"))))))

(deftest weather-query-ok
  (testing "Test when it is OK to query for FMI weather observations"
    (let [offset-millisec (.getOffset (t/default-time-zone)
                                      (.getMillis (DateTime/now)))
          hours (.toHours (TimeUnit/MILLISECONDS) offset-millisec)]
      ;; Timestamps are recorded in local time
      ;; Dummy test which kind of works, needs to be fixed properly at some time
      (is (true? (weather-query-ok? test-postgres (* hours 50)))))))

(deftest testbed-image-from-db
  (testing "Test for fetching a Testbed image"
    (let [not-null-row-id (first (j/query test-postgres
                                          (str "SELECT id FROM observations "
                                               "WHERE testbed_image IS "
                                               "NOT NULL")
                                          {:row-fn #(:id %)}))
          null-row-id (first (j/query test-postgres
                                      (str "SELECT id FROM observations "
                                           "WHERE testbed_image IS NULL")
                                      {:row-fn #(:id %)}))]
      (is (not (nil? (testbed-image-fetch test-postgres
                                          (str not-null-row-id)))))
      (is (nil? (testbed-image-fetch test-postgres
                                     (str null-row-id)))))))

(deftest beacon-search
  (testing "Querying beacon(s) for a observation"
    (let [first-id (first (j/query test-postgres
                                   (str "SELECT id FROM observations "
                                        "ORDER BY id ASC LIMIT 1")
                                   {:row-fn #(:id %)}))]
      (reset! beacon-count 0)
      (is (empty? (get-beacons test-postgres
                               (get-conf-value :beacon-name :use-sample true)
                               first-id)))
      (is (zero? @beacon-count))
      (is (= {:rssi -68
              :name "Beacon 1"}
             (first (get-beacons test-postgres
                                 (get-conf-value :beacon-name :use-sample true)
                                 (inc first-id)))))
      (is (= 1 @beacon-count))
      (is (= {:rssi -68
              :name "7C:EC:79:3F:BE:01"}
             (first (get-beacons test-postgres
                                 (get-conf-value :beacon-name :use-sample true)
                                 (inc (inc first-id))))))
      (is (= 1 @beacon-count)))))
