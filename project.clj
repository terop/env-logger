(defproject env-logger "0.1.0-SNAPSHOT"
	:description "A simple data logger"
	:url "http://example.com/FIXME"
    :dependencies [[org.clojure/clojure "1.5.1"]
                   [http-kit "2.1.18"]
                   [compojure "1.1.8"]
                   [postgresql "9.3-1101.jdbc4"]
                   [korma "0.3.1"]
                   [clj-time "0.6.0"]]
  :main env-logger.main
  :plugins [[lein-ring "0.8.10"]]
  :ring {:handler env-logger.main/main
         :auto-reload? true}
  :profiles
  {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]]
  		:resource-paths ["resources"]}})