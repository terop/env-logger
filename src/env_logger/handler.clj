(ns env-logger.handler
  "The main namespace of the application"
  (:require [cheshire.core :refer [generate-string parse-string]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :as route]
            [immutant.web :as web]
            [immutant.web.middleware :refer [wrap-development]]
            [ring.middleware.defaults :refer :all]
            [selmer.parser :refer [render-file]]
            [clj-time.core :as t]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [env-logger.grabber :refer [calculate-start-time
                                        get-latest-fmi-data]])
  (:gen-class))

(defroutes routes
  (GET "/" [ & params]
       (render-file "templates/plots.html"
                    (let [start-date (:startDate params)
                          end-date (:endDate params)]
                      (if (or (not (nil? start-date))
                              (not (nil? end-date)))
                        {:data (generate-string
                                (db/get-obs-within-interval
                                 db/postgres
                                 (if (not= "" start-date)
                                   start-date nil)
                                 (if (not= "" end-date)
                                   end-date nil)))
                         :start-date (if (not= "" start-date)
                                       start-date "")
                         :end-date (if (not= "" end-date)
                                     end-date nil)
                         :obs-dates (db/get-obs-start-and-end db/postgres)}
                        {:data (generate-string
                                (db/get-last-n-days-obs db/postgres 3))
                         :obs-dates (db/get-obs-start-and-end db/postgres)}))))
  (POST "/observations" [json-string]
        (let [start-time (calculate-start-time)
              start-time-int (t/interval (t/plus start-time
                                                 (t/seconds 45))
                                         (t/plus start-time
                                                 (t/minutes 3)))
              weather-data (if (t/within? start-time-int (t/now))
                             (get-latest-fmi-data (get-conf-value :fmi-api-key)
                                                  (get-conf-value :station-id))
                             {})
              obs-data (parse-string json-string true)]
          (generate-string (db/insert-observation
                            db/postgres
                            (assoc obs-data :weather-data weather-data)))))
  (GET "/observations" [] {:headers {"Content-Type" "application/json"}
                           :body (generate-string (db/get-all-obs
                                                   db/postgres))})
  ;; Serve static files
  (route/files "/")
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "APP_IP" "0.0.0.0")
        port (Integer/parseInt (get (System/getenv)
                                    "APP_PORT" "8080"))
        production? (get-conf-value :in-production)
        defaults-config (if production?
                          ;; TODO fix CSRF tokens
                          (assoc (assoc-in (assoc-in secure-site-defaults
                                                     [:security :anti-forgery]
                                                     false)
                                           [:security :hsts]
                                           (get-conf-value :use-hsts))
                                 :proxy (get-conf-value :use-proxy))
                          (assoc-in site-defaults
                                    [:security :anti-forgery] false))
        handler (if production?
                  (wrap-defaults routes defaults-config)
                  (wrap-development (wrap-defaults routes defaults-config)))
        opts {:host ip :port port}]
    (web/run handler opts)))
