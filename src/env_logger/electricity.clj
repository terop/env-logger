(ns env-logger.electricity
  "Namespace for electricity related functions"
  (:require [config.core :refer [env]]
            [java-time.api :as t]
            [next.jdbc :as jdbc]
            [ring.util.http-response :refer [bad-request]]
            [env-logger
             [db :as db]
             [render :refer [serve-json]]]))

(defn electricity-price
  "Returns data for the electricity price endpoint."
  [request]
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
            (serve-json (db/get-elec-price con
                                           (db/make-local-dt start-date "start")
                                           (when end-date
                                             (db/make-local-dt end-date
                                                               "end")))))
          (serve-json (db/get-elec-price con
                                         (t/minus (t/local-date-time)
                                                  (t/days (:initial-show-days
                                                           env)))
                                         nil)))))))
