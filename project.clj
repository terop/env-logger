(defproject env-logger "0.2.7-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.immutant/web "2.1.8"]
                 [compojure "1.6.0"]
                 [ring "1.6.1"]
                 [ring/ring-defaults "0.3.0"]
                 [org.postgresql/postgresql "42.1.1"]
                 [org.clojure/java.jdbc "0.7.0-alpha3"]
                 [honeysql "0.8.2"]
                 [clj-time "0.13.0"]
                 [cheshire "5.7.1"]
                 [selmer "1.10.7"]
                 [clj-http "3.6.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]
                 [com.yubico/yubico-validation-client2 "3.0.1"]
                 [buddy/buddy-hashers "1.2.0"]
                 [buddy/buddy-auth "1.4.1"]
                 [org.clojars.pntblnk/clj-ldap "0.0.12"]]
  :main env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.3"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}})
