(defproject env-logger "0.1.0-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [ring "1.4.0"]
                 [http-kit "2.1.19"]
                 [compojure "1.4.0"]
                 [postgresql "9.3-1101.jdbc4"]
                 [korma "0.4.2"]
                 [clj-time "0.10.0"]
                 [cheshire "5.5.0"]
                 [enlive "1.1.6"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]
  :main env-logger.main
  :plugins [[lein-ring "0.9.1"]]
  :ring {:handler env-logger.main/main
         :auto-reload? true}
  :profiles
  {:dev {:resource-paths ["resources"]}})
