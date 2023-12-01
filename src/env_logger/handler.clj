(ns env-logger.handler
  "The main namespace of the application"
  (:require [clojure set]
            [clojure.string :refer [ends-with?]]
            [config.core :refer [env]]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [java-time.api :as t]
            [jsonista.core :as j]
            [muuntaja.core :as m]
            [next.jdbc :as jdbc]
            [reitit.ring :as ring]
            [reitit.ring.middleware
             [muuntaja :as muuntaja]
             [parameters :as parameters]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware
             [defaults :refer [wrap-defaults
                               site-defaults
                               secure-site-defaults]]
             [reload :refer [wrap-reload]]]
            [ring.util.http-response :refer [bad-request found]]
            [taoensso.timbre :refer [error set-min-level!]]
            [env-logger
             [authentication :as auth]
             [db :as db]
             [electricity :refer [electricity-data
                                  parse-consumption-data-file]]
             [render :refer [serve-text serve-json serve-template]]
             [weather :refer [get-fmi-weather-data
                              store-weather-data?
                              get-weather-data
                              fetch-all-weather-data]]])
  (:import (java.time Instant
                      ZoneId)
           java.time.DateTimeException
           java.time.zone.ZoneRulesException)
  (:gen-class))

(def json-decode-opts
  "Options for jsonista read-value."
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
  (if-not (authenticated? (if (:identity request)
                            request (:session request)))
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
        (if-not data
          (serve-json {:data []
                       :rt-data []
                       :weather-data []})
          (serve-json {:data (assoc data :recorded
                                    (convert-epoch-ms-to-string
                                     (:recorded data)))
                       :rt-data (for [item rt-data]
                                  (assoc item :recorded
                                         (convert-epoch-ms-to-string
                                          (:recorded item))))
                       :weather-data (get-fmi-weather-data)}))))))

(defn get-plot-page-data
  "Returns data needed for rendering the plot page."
  [request]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [start-date-val (get (:params request) "startDate")
          start-date (when (seq start-date-val) start-date-val)
          end-date-val (get (:params request) "endDate")
          end-date (when (seq end-date-val) end-date-val)
          obs-dates (db/get-obs-date-interval con)
          logged-in? (authenticated? (:session request))
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
         :obs-dates {:current {:start start-date
                               :end end-date}}}
        {:obs-data (if logged-in?
                     (db/get-obs-days con
                                      initial-days)
                     (db/get-weather-obs-days con
                                              initial-days))
         :rt-data (when logged-in?
                    (db/get-ruuvitag-obs
                     con
                     (db/get-midnight-dt initial-days)
                     (t/local-date-time)
                     ruuvitag-locations))
         :obs-dates {:current {:start
                               (when (:end obs-dates)
                                 (t/format :iso-local-date
                                           (t/minus (t/local-date
                                                     (t/formatter
                                                      :iso-local-date)
                                                     (:end obs-dates))
                                                    (t/days initial-days))))
                               :end (:end obs-dates)}
                     :min-max {:start (:start obs-dates)
                               :end (:end obs-dates)}}}))))

(defn get-display-data
  "Returns the data to be displayed in the front-end."
  [request]
  (serve-json
   (merge {:weather-data
           (if-not (authenticated? (:session request))
             (:current (:fmi (get-weather-data)))
             (get-weather-data))}
          (merge {:mode (if (authenticated? (:session request)) "all" "weather")
                  :tb-image-basepath (:tb-image-basepath env)}
                 (when (authenticated? (:session request))
                   {:rt-names (:ruuvitag-locations env)
                    :rt-default-show (:ruuvitag-default-show env)
                    :rt-default-values (:ruuvitag-default-values env)}))
          (get-plot-page-data request))))

(defn handle-observation-insert
  "Handles the insertion of an observation to the database."
  [observation]
  (with-open [con (jdbc/get-connection db/postgres-ds)]
    (let [fmi-weather-data (get-fmi-weather-data)
          weather-data (when (store-weather-data? con (:time fmi-weather-data))
                         fmi-weather-data)]
      (db/insert-observation con
                             (assoc observation
                                    :weather-data weather-data)))))

(defn observation-insert
  "Function called when an observation is posted."
  [request]
  (fetch-all-weather-data)
  ;; Sleep a bit before continuing so that the possible weather data update
  ;; has the possibility to complete
  (Thread/sleep 1500)
  (if-not (auth/check-auth-code (get (:params request) "code"))
    auth/response-unauthorized
    (if-not (db/test-db-connection db/postgres-ds)
      auth/response-server-error
      (let [observation (j/read-value (get (:params request)
                                           "observation")
                                      json-decode-opts)]
        (if-not (= (count observation) 4)
          (bad-request "Bad request")
          (if (handle-observation-insert observation)
            (serve-text "OK") auth/response-server-error))))))

(defn rt-observation-insert
  "Function called when an RuuviTag observation is posted."
  [request]
  (if-not (auth/check-auth-code (get (:params request) "code"))
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (if-not (db/test-db-connection con)
        auth/response-server-error
        (if (pos? (db/insert-ruuvitag-observation
                   con
                   (j/read-value (get (:params request) "observation")
                                 json-decode-opts)))
          (serve-text "OK") auth/response-server-error)))))

(defn elec-consumption-data-upload
  "Function called on electricity consumption data upload."
  [request]
  (if (ends-with? (:filename
                   (get (:params request)
                        "consumption-file"))
                  ".csv")
    (let [parsed-data (parse-consumption-data-file (:tempfile
                                                    (get (:params request)
                                                         "consumption-file")))]
      (if (:error parsed-data)
        {:status "error"
         :cause (:error parsed-data)}
        (with-open [con (jdbc/get-connection db/postgres-ds)]
          (if (db/insert-elec-consumption-data con parsed-data)
            {:status "success"}
            {:status "error"}))))
    {:status "error"
     :cause "invalid-filename"}))

(defn tb-image-insert
  "Function called when an RuuviTag observation is posted."
  [request]
  (if-not (auth/check-auth-code (get (:params request) "code"))
    auth/response-unauthorized
    (with-open [con (jdbc/get-connection db/postgres-ds)]
      (if-not (db/test-db-connection con)
        auth/response-server-error
        (let [image-name (get (:params request) "name")]
          (if (re-find #"testbed-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}\+\d{4}\.png"
                       image-name)
            (if (db/insert-tb-image-name con
                                         (db/get-last-obs-id con)
                                         image-name)
              (serve-text "OK") auth/response-server-error)
            (bad-request "Bad request")))))))

(defn time-data
  "Returns the Unix timestamp and the UTC offset of the provided timezone."
  [request]
  (serve-json
   (try
     (let [tz (get (:params request) "timezone")]
       (if (and tz
                (ZoneId/of tz))
         {:timestamp (.getEpochSecond (Instant/now))
          :offset-hour (db/get-tz-offset tz)}
         {:error "Unspecified error"}))
     (catch ZoneRulesException zre
       (error zre "Cannot find timezone ID")
       {:error "Cannot find timezone ID"})
     (catch DateTimeException dte
       (error dte "Timezone ID has an invalid format")
       {:error "Timezone ID has an invalid format"}))))

(defn get-middleware
  "Returns the middlewares to be applied."
  []
  (let [dev-mode (:development-mode env)
        defaults (if dev-mode
                   site-defaults
                   secure-site-defaults)
        ;; CSRF protection is knowingly not implemented.
        ;; XSS protection is disabled as it is no longer recommended to
        ;; be enabled.
        ;; :params and :static options are disabled as Reitit handles them.
        defaults-config (-> defaults
                            (assoc-in [:security :anti-forgery]
                                      false)
                            (assoc-in [:security :xss-protection]
                                      false)
                            (assoc-in [:params :keywordize] false)
                            (assoc-in [:params :nested] false)
                            (assoc-in [:params :urlencoded] false)
                            (assoc :static false))]
    [[wrap-authorization auth/auth-backend]
     [wrap-authentication auth/jwe-auth-backend auth/auth-backend]
     parameters/parameters-middleware
     [wrap-defaults (if dev-mode
                      defaults-config
                      (if (:force-hsts env)
                        (assoc defaults-config :proxy true)
                        (-> defaults-config
                            (assoc-in [:security :ssl-redirect]
                                      false)
                            (assoc-in [:security :hsts]
                                      false))))]]))

(def app
  (ring/ring-handler
   (ring/router
    ;; Index
    [["/" {:get #(if-not (db/test-db-connection db/postgres-ds)
                   (serve-template "templates/error.html"
                                   {})
                   (serve-template "templates/chart.html"
                                   {:logged-in? (authenticated? (:session
                                                                 %))}))}]
     ;; Login and logout
     ["/login" {:get #(if-not (authenticated? (:session %))
                        (serve-template "templates/login.html" {})
                        (found (:app-url env)))
                :post auth/login-authenticate}]
     ["/logout" {:get (fn [_]
                        (assoc (found (:app-url env))
                               :session {}))}]
     ["/token-login" {:post auth/token-login}]
     ;; WebAuthn
     ["/register" {:get #(if-not (authenticated? (:session %))
                           auth/response-unauthorized
                           (serve-template "templates/register.html"
                                           {:username
                                            (name (get-in %
                                                          [:session
                                                           :identity]))}))}]
     ["/webauthn"
      ["/register" {:get auth/wa-prepare-register
                    :post auth/wa-register}]
      ["/login" {:get auth/wa-prepare-login
                 :post auth/wa-login}]]
     ;; Data queries
     ["/data"
      ["/display" {:get get-display-data}]
      ["/latest-obs" {:get get-latest-obs-data}]
      ["/weather" {:get #(if-not (authenticated? (if (:identity %)
                                                   % (:session %)))
                           auth/response-unauthorized
                           (serve-json (get-weather-data)))}]
      ["/elec-data" {:get electricity-data}]]
     ;; Observation storing
     ["/obs"
      ;; Standard observation
      ["/observation" {:post observation-insert}]
      ;; RuuviTag observation storage
      ["/rt-observation" {:post rt-observation-insert}]
      ;; Testbed image name storage
      ["/tb-image" {:post tb-image-insert}]]
     ;; Miscellaneous endpoints
     ["/misc"
      ;; Time data (timestamp and UTC offset)
      ["/time" {:get time-data}]
      ;; Electricity consumption data upload
      ["/elec-consumption" {:get #(if (authenticated? (:session %))
                                    (let [latest-dt
                                          (with-open [con
                                                      (jdbc/get-connection
                                                       db/postgres-ds)]
                                            (db/get-latest-elec-consumption-record-time
                                             con))]
                                      (serve-template
                                       "templates/elec-consumption-upload.html"
                                       {:app-url (:app-url env)
                                        :latest-dt latest-dt}))
                                    (found (:app-url env)))
                            :post #(if (authenticated? (:session %))
                                     (serve-json (elec-consumption-data-upload %))
                                     auth/response-unauthorized)}]]]
    {:data {:muuntaja m/instance
            :middleware [muuntaja/format-middleware]}})
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/create-default-handler))
   {:middleware (get-middleware)}))

(defn -main
  "Starts the web server."
  []
  (set-min-level! :info)
  (let [port (Integer/parseInt (get (System/getenv)
                                    "APP_PORT" "8080"))]
    (run-jetty (if (:development-mode env)
                 (wrap-reload #'app) #'app)
               {:port port})))
