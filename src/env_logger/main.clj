(ns env-logger.main
  (:require [compojure.route :as route]
            [compojure.handler :refer [site]]
      	    [compojure.core :refer [defroutes GET POST]]
            [ring.middleware.defaults :refer :all]
            [immutant.web :as web]
            [cheshire.core :refer [generate-string parse-string]]
            [net.cgrand.enlive-html :as html]
            [env-logger.db :as db]))

;; Template for the 'plots' page
(html/deftemplate plots "templates/plots.html"
  [params]
  [:span#plotData] (if (get params :all-data)
                     ;; All data
                     (html/content (generate-string (db/get-all-obs :rfc822)))
                     ;; Default
                     (html/content (generate-string
                                    (db/get-last-n-days-obs 3
                                                            :rfc822)))))

(defroutes routes
  (GET "/" [] "Welcome to the environment log viewer!")
  (GET "/add" [json-string] (db/insert-observation (parse-string json-string
                                                                 true)))
  (GET "/fetch" [ & date-format] {:headers {"Content-Type" "application/json"}
                                  :body (generate-string
                                         (db/get-all-obs
                                          (let [df (get date-format
                                                        :date-format "")]
                                            ;; TODO validate date format string
                                            (if (not= df "")
                                              (keyword df) :mysql))))})
  (GET "/plots" [ & params] (plots params))
  (route/resources "/static/")
  (route/not-found "<h2>Page not found.</h2>"))

(defn start
  "Starts the web server."
  []
  (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "localhost")
        port (Integer/parseInt (get (System/getenv)
                                    "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))]
    (web/run (wrap-defaults routes site-defaults) {:host ip :port port})))
