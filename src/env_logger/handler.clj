(ns env-logger.handler
  "The main namespace of the application"
  (:require [clojure set]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :refer [generate-string parse-string]]
            [java-time :as t]
            [compojure
             [core :refer [context defroutes GET POST]]
             [route :as route]]
            [next.jdbc :as jdbc]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :refer [wrap-defaults
                                              site-defaults
                                              secure-site-defaults]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.json :refer [wrap-json-params wrap-json-response]]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]
            [env-logger
             [authentication :as auth]
             [config :refer [get-conf-value]]
             [db :as db]
             [weather :refer [calculate-start-time
                              get-fmi-weather-data
                              weather-query-ok?
                              get-weather-data
                              fetch-all-weather-data]]])
  (:import java.time.Instant)
  (:gen-class))

(defn convert-epoch-ms-to-string
  "Converts an Unix epoch timestamp to a 'human readable' value."
  [epoch-ts]
  (t/format "d.L.Y HH:mm:ss"
            (t/plus (t/zoned-date-time
                     (str (Instant/ofEpochMilli epoch-ts)))
                    (t/hours (db/get-tz-offset
                              (get-conf-value :store-timezone))))))

(defn get-latest-obs-data
  "Get data for the latest observation."
  [request]
  (if-not (authenticated? request)
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (let [data (first (reverse (db/get-obs-days con 1)))
            rt-data (sort-by :location
                             (take (count (get-conf-value :ruuvitag-locations))
                                   (reverse (db/get-ruuvitag-obs
                                             con
                                             (t/minus (t/local-date-time)
                                                      (t/minutes 45))
                                             (t/local-date-time)
                                             (get-conf-value
                                              :ruuvitag-locations)))))]
        {:status 200
         :body {:data (assoc data :recorded
                             (convert-epoch-ms-to-string (:recorded data)))
                :rt-data (for [item rt-data]
                           (assoc item :recorded
                                  (convert-epoch-ms-to-string
                                   (:recorded item))))
                :weather-data (get-fmi-weather-data)}}))))

(defn get-plot-page-data
  "Returns data needed for rendering the plot page."
  [request]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-date (when (seq (:startDate (:params request)))
                       (:startDate (:params request)))
          end-date (when (seq (:endDate (:params request)))
                     (:endDate (:params request)))
          obs-dates (db/get-obs-date-interval con)
          logged-in? (authenticated? request)
          initial-days (get-conf-value :initial-show-days)
          common-values {:obs-dates obs-dates
                         :logged-in? logged-in?}]
      (merge common-values
             (if (or start-date end-date)
               {:data (generate-string
                       (if logged-in?
                         (db/get-obs-interval con
                                              {:start start-date
                                               :end end-date})
                         (db/get-weather-obs-interval con
                                                      {:start start-date
                                                       :end end-date})))
                :rt-data (generate-string
                          (when logged-in?
                            (db/get-ruuvitag-obs
                             con
                             (db/make-local-dt start-date "start")
                             (db/make-local-dt end-date "end")
                             (get-conf-value :ruuvitag-locations))))
                :start-date start-date
                :end-date end-date}
               {:data (generate-string
                       (if logged-in?
                         (db/get-obs-days con
                                          initial-days)
                         (db/get-weather-obs-days con
                                                  initial-days)))
                :rt-data (generate-string
                          (when logged-in?
                            (db/get-ruuvitag-obs
                             con
                             (t/minus (t/local-date-time)
                                      (t/days initial-days))
                             (t/local-date-time)
                             (get-conf-value :ruuvitag-locations))))
                :start-date (t/format (t/formatter :iso-local-date)
                                      (t/minus (t/local-date (t/formatter
                                                              :iso-local-date)
                                                             (:end obs-dates))
                                               (t/days initial-days)))
                :end-date (:end obs-dates)})
             {:weather-data (if logged-in?
                              (generate-string (get-weather-data))
                              (generate-string
                               (:current (:fmi (get-weather-data)))))}))))

(defn handle-observation-insert
  "Handles the insertion of an observation to the database."
  [obs-string]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-time (calculate-start-time)
          start-time-int (t/interval (t/plus start-time (t/minutes 4))
                                     (t/plus start-time (t/minutes 7)))
          weather-data (when (and (t/contains? start-time-int
                                               (t/zoned-date-time))
                                  (weather-query-ok? con 3))
                         (get-fmi-weather-data))]
      (db/insert-observation con
                             (assoc (parse-string obs-string
                                                  true)
                                    :weather-data weather-data)))))

(defroutes routes
  ;; Index and login
  (GET "/" request
    (if-not (db/test-db-connection db/postgres-ds)
      (render-file "templates/error.html"
                   {})
      (render-file "templates/chart.html"
                   (get-plot-page-data request))))
  (GET "/login" request
    (if-not (authenticated? request)
      (render-file "templates/login.html" {})
      (resp/redirect (str (get-conf-value :url-path) "/"))))
  (POST "/login" [] auth/login-authenticate)
  (GET "/logout" _
    (assoc (resp/redirect (str (get-conf-value :url-path) "/"))
           :session nil))
  (POST "/token-login" [] auth/token-login)
  ;; Parameter query
  (GET "/params" request
    (generate-string (merge {:mode (if (authenticated? request) "all" "weather")
                             :tb-image-basepath (get-conf-value
                                                 :tb-image-basepath)}
                            (when (authenticated? request)
                              {:rt-names (get-conf-value :ruuvitag-locations)
                               :rt-default-show (get-conf-value
                                                 :ruuvitag-default-show)
                               :rt-default-values
                               (get-conf-value :ruuvitag-default-values)}))))
  ;; WebAuthn routes
  (GET "/register" request
    (if-not (authenticated? request)
      auth/response-unauthorized
      (render-file "templates/register.html"
                   {:username (name (get-in request
                                            [:session :identity]))})))
  (context "/webauthn" []
    (GET "/register" [] auth/wa-prepare-register)
    (POST "/register" [] auth/wa-register)
    (GET "/login" [] auth/wa-prepare-login)
    (POST "/login" [] auth/wa-login))
  (GET "/get-latest-obs" [] get-latest-obs-data)
  (GET "/get-weather-data" request
    (if-not (authenticated? request)
      auth/response-unauthorized
      (generate-string (get-weather-data))))
  ;; Observation storing
  (POST "/observations" request
    (fetch-all-weather-data)
    (if-not (auth/check-auth-code (:code (:params request)))
      auth/response-unauthorized
      (if-not (db/test-db-connection db/postgres-ds)
        auth/response-server-error
        (if (handle-observation-insert (:obs-string (:params request)))
          "OK" auth/response-server-error))))
  ;; RuuviTag observation storage
  (POST "/rt-observations" request
    (if-not (auth/check-auth-code (:code (:params request)))
      auth/response-unauthorized
      (with-open [con (jdbc/get-connection db/postgres-ds)]
        (if-not (db/test-db-connection con)
          auth/response-server-error
          (if (pos? (db/insert-ruuvitag-observation
                     con
                     (parse-string (:observation (:params request)) true)))
            "OK" auth/response-server-error)))))
  ;; Testbed image name storage
  (POST "/tb-image" request
    (if-not (auth/check-auth-code (:code (:params request)))
      auth/response-unauthorized
      (with-open [con (jdbc/get-connection db/postgres-ds)]
        (if-not (db/test-db-connection con)
          auth/response-server-error
          (if (re-find #"testbed-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+\d{4}\.png"
                       (:name (:params request)))
            (if (db/insert-tb-image-name con
                                         (db/get-last-obs-id con)
                                         (:name (:params request)))
              "OK" auth/response-server-error)
            (resp/bad-request "Bad request"))))))
  ;; Serve static files
  (route/files "/")
  (route/not-found "404 Not Found"))

(defn -main
  "Starts the web server."
  []
  (let [port (Integer/parseInt (get (System/getenv)
                                    "APP_PORT" "8080"))
        production? (get-conf-value :in-production)
        config-no-csrf (assoc-in (if production?
                                   secure-site-defaults
                                   site-defaults)
                                 [:security :anti-forgery] false)
        defaults-config (if-not production?
                          config-no-csrf
                          (assoc (assoc-in config-no-csrf
                                           [:security :hsts]
                                           (get-conf-value :use-hsts))
                                 :proxy (get-conf-value :use-proxy)))
        handler (as-> routes $
                  (wrap-authorization $ auth/auth-backend)
                  (wrap-authentication $
                                       auth/jwe-auth-backend
                                       auth/auth-backend)
                  (wrap-defaults $ defaults-config)
                  (wrap-json-response $ {:pretty false})
                  (wrap-json-params $ {:keywords? true}))
        opts {:port port}]
    ;; Load initial weather data
    (fetch-all-weather-data)
    (run-jetty (if production?
                 handler
                 (wrap-reload handler))
               opts)))
