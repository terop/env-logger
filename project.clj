(defproject env-logger "0.2.11-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.immutant/web "2.1.10"]
                 [compojure "1.6.1"]
                 [ring "1.7.0"]
                 [ring/ring-defaults "0.3.2"]
                 [org.postgresql/postgresql "42.2.5"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [honeysql "0.9.4"]
                 [clj-time "0.14.4"]
                 [cheshire "5.8.1"]
                 [selmer "1.12.1"]
                 [clj-http "3.9.1"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]
                 [com.yubico/yubico-validation-client2 "3.0.2"]
                 [buddy/buddy-hashers "1.3.0"]
                 [buddy/buddy-auth "2.1.0"]
                 [org.clojars.pntblnk/clj-ldap "0.0.16"]
                 [org.influxdb/influxdb-java "2.13"]]
  :main env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.3"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}})
