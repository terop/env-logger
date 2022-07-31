(ns env-logger.handler
  "The main namespace of the application"
  (:require [clojure set]
            [config.core :refer [env]]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [jsonista.core :as j]
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
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]
            [env-logger
             [authentication :as auth]
             [db :as db]
             [weather :refer [calculate-start-time
                              get-fmi-weather-data
                              store-weather-data?
                              get-weather-data
                              fetch-all-weather-data]]])
  (:import java.time.Instant)
  (:gen-class))

(def json-decode-opts
  "Options for read-value"
  (j/object-mapper {:decode-key-fn true}))

(defn convert-epoch-ms-to-string
  "Converts an Unix epoch timestamp to a 'human readable' value."
  [epoch-ts]
  (t/format "d.L.Y HH:mm:ss"
            (t/plus (t/zoned-date-time
                     (str (Instant/ofEpochMilli epoch-ts)))
                    (t/hours (db/get-tz-offset
                              (:store-timezone env))))))

(defn get-latest-obs-data
  "Get data for the latest observation."
  [request]
  (if-not (authenticated? request)
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (let [data (first (reverse (db/get-obs-days con 1)))
            rt-data (sort-by :location
                             (take (count (:ruuvitag-locations env))
                                   (reverse (db/get-ruuvitag-obs
                                             con
                                             (t/minus (t/local-date-time)
                                                      (t/minutes 45))
                                             (t/local-date-time)
                                             (:ruuvitag-locations env)))))]
        (j/write-value-as-string
         (if-not data
           {:status 500}
           {:status 200
            :body {:data (assoc data :recorded
                                (convert-epoch-ms-to-string (:recorded data)))
                   :rt-data (for [item rt-data]
                              (assoc item :recorded
                                     (convert-epoch-ms-to-string
                                      (:recorded item))))
                   :weather-data (get-fmi-weather-data)}}))))))

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
          initial-days (:initial-show-days env)
          ruuvitag-locations (:ruuvitag-locations env)]
      (if (or start-date end-date)
        {:obs-data (if logged-in?
                     (db/get-obs-interval con
                                          {:start start-date
                                           :end end-date})
                     (db/get-weather-obs-interval con
                                                  {:start start-date
                                                   :end end-date}))
         :rt-data (when logged-in?
                    (db/get-ruuvitag-obs
                     con
                     (db/make-local-dt start-date "start")
                     (db/make-local-dt end-date "end")
                     ruuvitag-locations))
         :start-date start-date
         :end-date end-date}
        {:obs-data (if logged-in?
                     (db/get-obs-days con
                                      initial-days)
                     (db/get-weather-obs-days con
                                              initial-days))
         :rt-data (when logged-in?
                    (db/get-ruuvitag-obs
                     con
                     (t/minus (t/local-date-time)
                              (t/days initial-days))
                     (t/local-date-time)
                     ruuvitag-locations))
         :start-date (when (:end obs-dates)
                       (t/format (t/formatter :iso-local-date)
                                 (t/minus (t/local-date (t/formatter
                                                         :iso-local-date)
                                                        (:end obs-dates))
                                          (t/days initial-days))))
         :end-date (:end obs-dates)}))))

(defn handle-observation-insert
  "Handles the insertion of an observation to the database."
  [obs-string]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-time (calculate-start-time)
          start-time-int (t/interval (t/plus start-time (t/minutes 4))
                                     (t/plus start-time (t/minutes 7)))
          weather-data (when (and (t/contains? start-time-int
                                               (t/zoned-date-time))
                                  (store-weather-data? con))
                         (get-fmi-weather-data))]
      (db/insert-observation con
                             (assoc (j/read-value obs-string
                                                  json-decode-opts)
                                    :weather-data weather-data)))))

(defroutes routes
  ;; Index and login
  (GET "/" request
    (if-not (db/test-db-connection db/postgres-ds)
      (render-file "templates/error.html"
                   {})
      (render-file "templates/chart.html"
                   {:logged-in? (authenticated? request)
                    :obs-dates (db/get-obs-date-interval db/postgres-ds)})))
  (GET "/login" request
    (if-not (authenticated? request)
      (render-file "templates/login.html" {})
      (resp/redirect (:application-url env))))
  (POST "/login" [] auth/login-authenticate)
  (GET "/logout" _
    (assoc (resp/redirect (:application-url env))
           :session {}))
  (POST "/token-login" [] auth/token-login)
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
  ;; Data query
  (GET "/display-data" request
    (resp/content-type
     (resp/response
      (j/write-value-as-string
       (merge {:weather-data
               (if-not (authenticated? request)
                 (:current (:fmi (get-weather-data)))
                 (get-weather-data))}
              (merge {:mode (if (authenticated? request) "all" "weather")
                      :tb-image-basepath (:tb-image-basepath env)}
                     (when (authenticated? request)
                       {:rt-names (:ruuvitag-locations env)
                        :rt-default-show (:ruuvitag-default-show env)
                        :rt-default-values (:ruuvitag-default-values env)}))
              (get-plot-page-data request))))
     "application/json"))
  (GET "/get-latest-obs" [] get-latest-obs-data)
  (GET "/get-weather-data" request
    (if-not (authenticated? request)
      auth/response-unauthorized
      (resp/content-type
       (resp/response
        (j/write-value-as-string
         (get-weather-data)))
       "application/json")))
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
                     (j/read-value (:observation (:params request))
                                   json-decode-opts)))
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
        opts {:port port}
        dev-mode (:development-mode env)
        defaults (if dev-mode
                   site-defaults
                   secure-site-defaults)
        ;; CSRF protection is knowingly not implemented
        defaults-config (assoc-in defaults [:security :anti-forgery] false)
        handler (as-> routes $
                  (wrap-authorization $ auth/auth-backend)
                  (wrap-authentication $
                                       auth/jwe-auth-backend
                                       auth/auth-backend)
                  (wrap-json-params $ {:keywords? true})
                  (wrap-defaults $ (if dev-mode
                                     defaults-config
                                     (if (:force-hsts env)
                                       (assoc defaults-config :proxy true)
                                       (-> defaults-config
                                           (assoc-in [:security :ssl-redirect]
                                                     false)
                                           (assoc-in [:security :hsts]
                                                     false))))))]
    (run-jetty (if dev-mode
                 (wrap-reload handler)
                 handler)
               opts)))
