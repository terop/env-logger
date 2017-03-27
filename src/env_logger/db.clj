(ns env-logger.db
  "Namespace containing the application's database function"
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [clj-time.jdbc]
            [clojure.java.jdbc :as j]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
            [clj-ldap.client :as ldap]
            [clojure.tools.logging :as log]
            [env-logger.config :refer :all]))

(let [db-host (get (System/getenv)
                   "POSTGRESQL_DB_HOST"
                   (db-conf :host))
      db-port (get (System/getenv)
                   "POSTGRESQL_DB_PORT"
                   (db-conf :port))
      db-name (get (System/getenv)
                   "POSTGRESQL_DB_NAME"
                   (db-conf :name))
      db-user (get (System/getenv)
                   "POSTGRESQL_DB_USERNAME"
                   (db-conf :username))
      db-password (get (System/getenv)
                       "POSTGRESQL_DB_PASSWORD"
                       (db-conf :password))]
  (def postgres {:classname "org.postgresql.Driver"
                 :subprotocol "postgresql"
                 :subname (format "//%s:%s/%s"
                                  db-host db-port db-name)
                 :user db-user
                 :password db-password}))

(defonce beacon-count (atom 0))

;; User data functions
(defn get-yubikey-id
  "Returns the Yubikey ID(s) of a user in a set. Returns an empty set
  if the ID is not found."
  [db-con username]
  (set (j/query db-con
                (sql/format (sql/build :select [:yubikey_id]
                                       :from [[:users :u]]
                                       :join [:yubikeys
                                              [:= :u.user_id
                                               :yubikeys.user_id]]
                                       :where [:= :u.username
                                               username]))
                {:row-fn #(:yubikey_id %)})))

(defn get-user-data
  "Returns the password hash and Yubikey ID(s) of the user with the given
  username. Returns nil if the user is not found."
  [db-con username]
  (let [result (j/query db-con
                        (sql/format (sql/build :select [:pw_hash]
                                               :from :users
                                               :where [:= :username
                                                       username]))
                        {:row-fn #(:pw_hash %)})
        key-ids (get-yubikey-id db-con username)]
    (when (pos? (count result))
      {:pw-hash (first result)
       :yubikey-ids key-ids})))

(defn get-password-from-ldap
  "Fetches a user's password hash from LDAP. Returns nil if the user is
  not found."
  [username]
  (:userPassword (ldap/get (ldap/connect {:host (format "%s:%s"
                                                        (ldap-conf :host)
                                                        (ldap-conf :port))
                                          :bind-dn (ldap-conf :bind-dn)
                                          :password (ldap-conf :password)})
                           (format "cn=%s,ou=users,%s"
                                   username
                                   (ldap-conf :base-dn))
                           [:userPassword])))

;; Observation functions
(defn insert-observation
  "Inserts a observation to the database. Optionally corrects the temperature
  with an offset."
  [db-con observation]
  (if (= 6 (count observation))
    (j/with-db-transaction [t-con db-con]
      (try
        (let [offset (if (get-conf-value :correction :k :enabled)
                       (get-conf-value :correction :k :offset) 0)
              image-name (:image_name
                          (first (j/query t-con
                                          (sql/format
                                           (sql/build
                                            :select [:image_name]
                                            :from [:yardcam_images])))))
              obs-id (:id (first (j/insert! t-con
                                            :observations
                                            {:recorded (f/parse
                                                        (:timestamp
                                                         observation))
                                             :temperature (- (:inside_temp
                                                              observation)
                                                             offset)
                                             :brightness (:inside_light
                                                          observation)
                                             :yc_image_name image-name
                                             :testbed_image (:testbed-image
                                                             observation)})))]
          (if (pos? obs-id)
            (if (every? pos?
                        (for [beacon (:beacons observation)]
                          (:id (first (j/insert! t-con
                                                 :beacons
                                                 {:obs_id obs-id
                                                  :mac_address (:mac beacon)
                                                  :rssi (:rssi beacon)})))))
              (let [weather-data (:weather-data observation)]
                (if (zero? (count weather-data))
                  ;; No weather data
                  true
                  (if (pos? (:id (first (j/insert! t-con
                                                   :weather_data
                                                   {:obs_id obs-id
                                                    :time (f/parse
                                                           (:date
                                                            weather-data))
                                                    :temperature (:temperature
                                                                  weather-data)
                                                    :cloudiness
                                                    (:cloudiness
                                                     weather-data)}))))
                    true
                    (do
                      (log/info (str "Database insert: rolling back "
                                     "transaction after weather data insert"))
                      (j/db-set-rollback-only! t-con)
                      false))))
              (do
                (log/info (str "Database insert: rolling back "
                               "transaction after beacon scan insert"))
                (j/db-set-rollback-only! t-con)
                false))))
        (catch org.postgresql.util.PSQLException pge
          (log/error (str "Database insert failed, message:"
                          (.getMessage pge)))
          (j/db-set-rollback-only! t-con)
          false)))
    false))

(defn format-datetime
  "Changes the timezone and formats the datetime with the given formatter."
  [datetime formatter]
  (l/format-local-time (t/to-time-zone datetime (t/time-zone-for-id
                                                 "Europe/Helsinki"))
                       formatter))

(defn validate-date
  "Checks if the given date is nil or if non-nil, it is in the dd.mm.yyyy
  or d.m.yyyy format."
  [date]
  (if (nil? date)
    true
    (not (nil? (and date
                    (re-find #"\d{1,2}\.\d{1,2}\.\d{4}" date))))))

(defn make-date-dt
  "Formats a date to be SQL datetime compatible. Mode is either start or end."
  [date mode]
  (f/parse (f/formatter "d.M.y H:m:s")
           (format "%s %s" date (if (= mode "start")
                                  "00:00:00"
                                  "23:59:59"))))

(defmacro get-by-interval
  "Fetches observations in an interval using the provided function."
  [fetch-fn db-con start-date end-date dt-column]
  (let [start-dt (gensym 'start)
        end-dt (gensym 'end)]
    `(if (or (not (validate-date ~start-date))
             (not (validate-date ~end-date)))
       ;; Either date is invalid
       ()
       (let [~start-dt (if ~start-date
                         (make-date-dt ~start-date "start")
                         ;; Hack to avoid SQL WHERE hacks
                         (t/date-time 2010 1 1))
             ~end-dt (if ~end-date
                       (make-date-dt ~end-date "end")
                       ;; Hack to avoid SQL WHERE hacks
                       (t/now))]
         (~fetch-fn ~db-con :where [:and
                                    [:>= ~dt-column ~start-dt]
                                    [:<= ~dt-column ~end-dt]])))))

(defn get-beacons
  "Returns beacon's name and the RSSI for given observation ID. The name is
  looked up from the :beacon-name parameter in the configuration file. If the
  name is not found, the MAC address is returned."
  [db-con beacon-names observation-id]
  (let [beacons (j/query db-con
                         (sql/format (sql/build :select [:mac_address :rssi]
                                                :from :beacons
                                                :where [:= :obs_id
                                                        observation-id]))
                         {:row-fn (fn [row]
                                    {:name (get beacon-names
                                                (:mac_address row)
                                                (:mac_address row))
                                     :rssi (:rssi row)})})]
    (swap! beacon-count (fn [current new-value]
                          (if (> new-value current)
                            new-value current)) (count beacons))
    beacons))

(defn get-observations
  "Fetches observations optionally filtered by a provided SQL WHERE clause.
  Limiting rows is possible by providing row count with the :limit argument."
  [db-con & {:keys [where limit]
             :or {where nil
                  limit nil}}]
  (let [base-query {:select [:o.brightness
                             :o.temperature
                             :o.recorded
                             [:w.temperature "o_temperature"]
                             :w.cloudiness
                             :o.yc_image_name
                             :o.id]
                    :from [[:observations :o]]
                    :left-join [[:weather-data :w]
                                [:= :o.id :w.obs_id]]}
        where-query (if where
                      (sql/build base-query :where where)
                      base-query)
        limit-query (if limit
                      (sql/build where-query :limit limit
                                 :order-by [[:o.id :desc]])
                      (sql/build where-query :order-by [[:o.id :asc]]))
        beacon-names (get-conf-value :beacon-name)]
    (j/query db-con
             (sql/format limit-query)
             {:row-fn #(merge %
                              {:recorded (format-datetime
                                          (:recorded %)
                                          :date-hour-minute-second)
                               :beacons (get-beacons db-con
                                                     beacon-names
                                                     (:id %))})})))

(defn get-obs-days
  "Fetches the observations from the last N days."
  [db-con n]
  (get-observations db-con
                    :where [:>= :recorded
                            (t/minus (t/now)
                                     (t/days n))]))

(defn get-obs-interval
  "Fetches observations in an interval between the provided dates."
  [db-con start-date end-date]
  (get-by-interval get-observations
                   db-con
                   start-date
                   end-date
                   :recorded))

(defn get-weather-observations
  "Fetches weather observations filtered by the provided SQL WHERE clause."
  [db-con & {:keys [where]}]
  (j/query db-con
           (sql/format (sql/build :select [:w.time
                                           [:w.temperature "o_temperature"]
                                           :w.cloudiness
                                           :o.yc_image_name
                                           :o.id]
                                  :from [[:weather-data :w]]
                                  :join [[:observations :o]
                                         [:= :w.obs_id
                                          :o.id]]
                                  :where where
                                  :order-by [[:w.id :asc]]))
           {:row-fn #(merge %
                            {:time (format-datetime
                                    (:time %)
                                    :date-hour-minute-second)})}))


(defn get-weather-obs-days
  "Fetches the weather observations from the last N days."
  [db-con n]
  (get-weather-observations db-con
                            :where [:>= :time
                                    (t/minus (t/now)
                                             (t/days n))]))

(defn get-weather-obs-interval
  "Fetches weather observations in an interval between the provided dates."
  [db-con start-date end-date]
  (get-by-interval get-weather-observations
                   db-con
                   start-date
                   end-date
                   :time))

(defn get-obs-start-and-end
  "Fetches the start (first) and end (last) dates of all observations."
  [db-con]
  (let [formatter (f/formatter "d.M.y")
        result (j/query db-con
                        (sql/format (sql/build :select [[:%min.recorded "start"]
                                                        [:%max.recorded "end"]]
                                               :from :observations)))]
    (if (= 1 (count result))
      {:start (f/unparse formatter (:start (first result)))
       :end (f/unparse formatter (:end (first result)))}
      {:start "" :end ""})))

(defn insert-yc-image-name
  "Stores the name of the latest Yardcam image. Rows from the table are
  deleted before a new row is inserted."
  [db-con image-name]
  (let [result (j/query db-con
                        (sql/format (sql/build :select [:image_id]
                                               :from :yardcam_images)))]
    (when (pos? (count result))
      (j/execute! db-con "DELETE FROM yardcam_images"))
    (= 1 (count (j/insert! db-con
                           :yardcam_images
                           {:image_name image-name})))))

(defn weather-query-ok?
  "Tells whether it is OK to query the FMI API for weather observations.
  Criteria for being OK is that the last observation's timestamp does not lie
  within the [now-waittime,now] interval. Wait time is to be provided in
  minutes."
  [db-con wait-time]
  (let [obs-recorded (:recorded (first
                                 (j/query db-con
                                          "SELECT recorded FROM observations
                                          WHERE id = (SELECT obs_id FROM
                                          weather_data ORDER BY id DESC
                                          LIMIT 1)")))]
    (if (nil? obs-recorded)
      true
      (not (t/within? (t/interval (t/minus (t/now) (t/minutes wait-time))
                                  (t/now))
                      obs-recorded)))))

(defn testbed-image-fetch
  "Returns the Testbed image corresponding to the provided ID."
  [db-con id]
  (first (j/query db-con
                  (sql/format (sql/build :select [:testbed_image]
                                         :from :observations
                                         :where [:= :id
                                                 (Integer/parseInt id)]))
                  {:row-fn #(:testbed_image %)})))

(defn get-last-obs-id
  "Returns the ID of the last observation."
  [db-con]
  (first (j/query db-con
                  (sql/format (sql/build :select [[:%max.id "id"]]
                                         :from :observations))
                  {:row-fn #(:id %)})))

(defn store-testbed-image
  "Saves a Testbed image and associates it with given observation ID. Returns
  the number of modified rows."
  [db-con obs-id tb-image]
  (first (j/update! db-con
                    :observations
                    {:testbed_image tb-image}
                    ["id = ?" obs-id])))
