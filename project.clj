(defproject env-logger "0.1.0-SNAPSHOT"
	:description "A simple data logger"
	:url "http://example.com/FIXME"
    :dependencies [[org.clojure/clojure "1.5.1"]
                   [http-kit "2.1.18"]
                   [ring "1.3.0"]
                   [compojure "1.1.8"]
                   [postgresql "9.3-1101.jdbc4"]
                   [korma "0.3.2"]
                   [clj-time "0.7.0"]
                   [org.clojure/data.json "0.2.4"]
                   [enlive "1.1.5"]]
  :main env-logger.main
  :plugins [[lein-ring "0.8.10"]]
  :ring {:handler env-logger.main/main
         :auto-reload? true}
  :profiles
  {:dev {:resource-paths ["resources"]}})
