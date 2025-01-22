(ns env-logger.electricity
  "Namespace for electricity related functions"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.instant :refer [read-instant-timestamp]]
            [clojure.string :as str]
            [buddy.auth :refer [authenticated?]]
            [config.core :refer [env]]
            [java-time.api :as jt]
            [next.jdbc :as jdbc]
            [ring.util.http-response :refer [bad-request]]
            [env-logger
             [authentication :as auth]
             [db :as db]
             [render :refer [serve-json]]]))

(defn electricity-data
  "Returns data for the electricity data endpoint."
  [request]
  (if-not (authenticated? (if (:identity request)
                            request (:session request)))
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (let [start-date-val (get (:params request) "startDate")
            start-date (when (seq start-date-val) start-date-val)
            end-date-val (get (:params request) "endDate")
            end-date (when (seq end-date-val) end-date-val)]
        (if-not (:show-elec-price env)
          (serve-json {:error "not-enabled"})
          (if (or start-date end-date)
            (if-not start-date
              (bad-request "Missing parameter")
              (serve-json {:data-hour (db/get-elec-data-hour
                                       con
                                       (db/make-local-dt start-date
                                                         "start")
                                       (when end-date
                                         (db/make-local-dt end-date
                                                           "end")))
                           :data-day (db/get-elec-data-day con
                                                           start-date
                                                           end-date)
                           :dates {:current {:start start-date
                                             :end end-date}}
                           :month-price-avg (db/get-month-avg-elec-price con)
                           :month-consumption (db/get-month-elec-consumption
                                               con)}))
            (let [start-date (db/get-midnight-dt (:initial-show-days env))]
              (serve-json {:data-hour (db/get-elec-data-hour con
                                                             start-date
                                                             nil)
                           :data-day (db/get-elec-data-day
                                      con
                                      (db/add-tz-offset-to-dt start-date)
                                      nil)
                           :dates {:current {:start
                                             (jt/format :iso-local-date
                                                        (if (= (:display-timezone
                                                                env) "UTC")
                                                          (jt/plus start-date
                                                                   (jt/hours
                                                                    (db/get-tz-offset
                                                                     (:store-timezone
                                                                      env))))
                                                          start-date))}
                                   :max (db/get-elec-price-interval-end con)
                                   :min (db/get-elec-consumption-interval-start
                                         con)}
                           :month-price-avg (db/get-month-avg-elec-price con)
                           :month-consumption (db/get-month-elec-consumption
                                               con)}))))))))

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
