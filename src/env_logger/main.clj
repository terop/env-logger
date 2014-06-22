(ns env-logger.main
  (:require [compojure.route :as route]            
            [compojure.handler :refer [site]]
      	    [compojure.core :refer [defroutes GET POST]]
            [ring.middleware.reload :refer [wrap-reload]]
      	    [org.httpkit.server :as kit]
            [env-logger.db :as db]
            [clojure.data.json :as json]
            [net.cgrand.enlive-html :as html]))

; Template for the 'plots' page
(html/deftemplate plots "templates/plots.html"
  []
  [:span#plotData] (html/content (json/write-str (db/get-all-observations :rfc822))))

(defroutes routes
  (GET "/" [] "Hello HTTP!")
  (GET "/add" [json-string] (db/insert-observation (json/read-str json-string
                                    :key-fn keyword)))
  (GET "/fetch" [ & date-format] {:headers {"Content-Type" "application/json"}
                                  :body (json/write-str (db/get-all-observations
                                    (let [df (get date-format :date-format "")]
                                      ; TODO validate date format string
                                      (if (not= df "") (keyword df) :mysql))))})
  (GET "/plots" [] (plots))
  (route/resources "/static/")
  (route/not-found "<h2>Page not found.</h2>"))

(defn in-dev?
  "Checks in which environment (development or production) the program 
  is running"
  []
  (if (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP") false true))

(defn -main [& args]
  (let [port (Integer/parseInt (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))]
    (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "0.0.0.0")]
      ; Hack to enable reload in development mode
      (let [handler (if (in-dev?)
                      (wrap-reload (site #'routes))
                      (site #'routes))]
        (kit/run-server handler {:ip ip :port port})))))
