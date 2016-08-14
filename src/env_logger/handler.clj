(ns env-logger.handler
  "The main namespace of the application"
  (:require [cheshire.core :refer [generate-string parse-string]]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.route :as route]
            [env-logger.config :refer [get-conf-value]]
            [env-logger.db :as db]
            [immutant.web :as web]
            [immutant.web.middleware :refer [wrap-development]]
            [ring.middleware.defaults :refer :all]
            [selmer.parser :refer [render-file]]))

(defroutes routes
  (GET "/" [] (render-file "templates/index.html" {}))
  (GET "/add" [json-string] (generate-string (db/insert-observation
                                              db/postgres
                                              (parse-string json-string true))))
  (GET "/fetch" [] {:headers {"Content-Type" "application/json"}
                    :body (generate-string (db/get-all-obs db/postgres))})
  (GET "/plots" [ & params]
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
                                     end-date nil)}
                        {:data (generate-string
                                (db/get-last-n-days-obs db/postgres 3))}))))
  ;; Serve static files
  (route/files "/" {:root "resources"})
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "APP_IP" "0.0.0.0")
        port (Integer/parseInt (get (System/getenv)
                                    "APP_PORT" "8080"))
        production? (get-conf-value :in-production)
        defaults-config (if production?
                          (assoc (assoc-in secure-site-defaults [:security
                                                                 :anti-forgery]
                                           false) :proxy true)
                          (assoc-in site-defaults [:security :anti-forgery]
                                    false))
        handler (if production?
                  ;; TODO fix CSRF tokens
                  (wrap-defaults routes defaults-config)
                  (wrap-development (wrap-defaults routes defaults-config)))
        opts {:host ip :port port}]
    (web/run handler opts)))
