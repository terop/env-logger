(ns env-logger.main
  (:require [compojure.route :as route]            
            [compojure.handler :refer [site]] 
      	    [compojure.core :refer [defroutes GET POST]]
      	    [org.httpkit.server :as kit]
            [env-logger.db :as db]
            [clojure.data.json :as json]))
 
(defroutes routes
  (GET "/" [] "Hello HTTP!")
  (GET "/add" [json-string] (db/insert-observation (json/read-str json-string
                                    :key-fn keyword)))
  ;(route/files "/static/") ;; static file url prefix /static, in `public` folder
  (route/not-found "<p>Page not found.</p>"))

(defn -main [& args]
  (let [port (Integer/parseInt (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_PORT" "8080"))]
    (let [ip (get (System/getenv) "OPENSHIFT_CLOJURE_HTTP_IP" "0.0.0.0")]
      (kit/run-server (site #'routes) {:ip ip :port port}))))
