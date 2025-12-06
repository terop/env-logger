(ns env-logger.electricity
  "Namespace for electricity related functions"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.instant :refer [read-instant-timestamp]]
            [clojure.string :as str]
            [config.core :refer [env]]
            [java-time.api :as jt]
            [next.jdbc :as jdbc]
            [ring.util.http-response :refer [bad-request]]
            [terop.openid-connect-auth :refer [access-ok?]]
            [env-logger.authentication :as auth]
            [env-logger.db :as db]
            [env-logger.render :refer [serve-json]]))

(defn calculate-month-cost
  "Calculates the electricity cost for the current month."
  []
  (let [today (jt/local-date)
        month-start (jt/minus today (jt/days (dec (jt/as today :day-of-month))))]
    (db/get-interval-elec-cost db/postgres-ds
                               (db/make-local-dt (str month-start) "start")
                               (db/make-local-dt (str today) "end"))))

(defn calculate-interval-cost
  "Calculates the electricity cost for an given interval."
  [start end]
  (db/get-interval-elec-cost db/postgres-ds
                             start end))

(defn electricity-data
  "Returns data for the electricity data endpoint."
  [request]
  (if-not (access-ok? (:oid-auth env) request)
    auth/response-unauthorized
    (if-not (:show-elec-price env)
      (serve-json {:error "not-enabled"})
      (with-open [con (jdbc/get-connection db/postgres-ds)]
        (let [start-date-val (get (:params request) "startDate")
              start-date (when (seq start-date-val) start-date-val)
              end-date-val (get (:params request) "endDate")
              end-date (when (seq end-date-val) end-date-val)
              add-fees-val (get (:params request) "addFees")
              add-fees (when (seq add-fees-val) (Boolean/parseBoolean add-fees-val))
              resp-data {:month-price-avg (db/get-month-avg-elec-price con
                                                                       add-fees)
                         :month-consumption (db/get-month-elec-consumption con)
                         :month-cost (calculate-month-cost)
                         :price-thresholds (:elec-price-thresholds env)}]
          (if (or start-date end-date)
            (if-not start-date
              (bad-request "Missing parameter")
              (serve-json (merge resp-data
                                 {:data-hour (db/get-elec-data-hour
                                              con
                                              (db/make-local-dt start-date
                                                                "start")
                                              (when end-date
                                                (db/make-local-dt end-date
                                                                  "end"))
                                              add-fees)
                                  :data-day (db/get-elec-data-day con
                                                                  start-date
                                                                  end-date
                                                                  add-fees)
                                  :dates {:current {:start start-date
                                                    :end end-date}}
                                  :interval-cost (calculate-interval-cost
                                                  (db/make-local-dt start-date "start")
                                                  (db/make-local-dt end-date "end"))})))
            (let [start-date (db/get-midnight-dt (:initial-show-days env))
                  interval-end (db/get-elec-price-interval-end con)]
              (serve-json (merge resp-data
                                 {:data-hour (db/get-elec-data-hour con
                                                                    start-date
                                                                    nil
                                                                    add-fees)
                                  :data-day (db/get-elec-data-day
                                             con
                                             (db/add-tz-offset-to-dt start-date)
                                             nil
                                             add-fees)
                                  :dates {:current {:start
                                                    (jt/format
                                                     :iso-local-date
                                                     (if (= (:display-timezone
                                                             env) "UTC")
                                                       (jt/plus start-date
                                                                (jt/hours
                                                                 (db/get-tz-offset
                                                                  (:store-timezone env))))
                                                       start-date))}
                                          :max interval-end
                                          :min (db/get-elec-consumption-interval-start
                                                con)}
                                  :interval-cost (calculate-interval-cost
                                                  start-date
                                                  (db/make-local-dt interval-end
                                                                    "end"))})))))))))

(defn electricity-price-minute
  "Returns data for the electricity price with 15 minute resolution endpoint."
  [request]
  (if-not (access-ok? (:oid-auth env) request)
    auth/response-unauthorized
    (if-not (:show-elec-price env)
      (serve-json {:error "not-enabled"})
      (let [date-val (get (:params request) "date")
            date (when (seq date-val) date-val)
            get-date (get (:params request) "getDate")
            add-fees-val (get (:params request) "addFees")
            add-fees (when (seq add-fees-val) (Boolean/parseBoolean add-fees-val))]
        (if-not date
          (bad-request "Missing parameter")
          (let [resp-data {:prices (db/get-elec-price-minute
                                    db/postgres-ds
                                    (db/make-local-dt date "start")
                                    (db/make-local-dt date "end")
                                    add-fees)}]
            (serve-json
             (if get-date
               (assoc resp-data
                      :date-min
                      (db/get-elec-price-minute-interval-start db/postgres-ds))
               resp-data))))))))

(defn parse-consumption-data-file
  "Parses CSV file with electricity consumption data."
  [data-file]
  (with-open [reader (io/reader data-file)]
    (let [rows (doall (csv/read-csv reader {:separator \;}))]
      (if (< (count rows) 2)
        {:error "no-data"}
        (if (not= (count (first rows)) 8)
          {:error "invalid-format"}
          (for [row (rest rows)]
            [(read-instant-timestamp (nth row 5))
             (Float/parseFloat (str/replace (nth row 6) "," "."))]))))))
