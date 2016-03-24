(defproject env-logger "0.1.0-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.immutant/web "2.1.3"]
                 [compojure "1.5.0"]
                 [ring "1.4.0"]
                 [ring/ring-defaults "0.2.0"]
                 [org.postgresql/postgresql "9.4.1207"]
                 [korma "0.4.2"]
                 [clj-time "0.11.0"]
                 [cheshire "5.5.0"]
                 [selmer "1.0.2"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]
  :main env-logger.handler
  :profiles
  {:dev {:resource-paths ["resources"]}})
