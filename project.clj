(defproject env-logger "0.2.11-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.immutant/web "2.1.10"]
                 [compojure "1.6.2"]
                 [ring "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [org.postgresql/postgresql "42.2.16"]
                 [org.clojure/java.jdbc "0.7.11"]
                 [honeysql "1.0.444"]
                 [clj-time "0.15.2"]
                 [cheshire "5.10.0"]
                 [selmer "1.12.31"]
                 [clj-http "3.10.3"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.slf4j/slf4j-log4j12 "1.7.30"]
                 [com.yubico/yubico-validation-client2 "3.1.0"]
                 [buddy/buddy-hashers "1.6.0"]
                 [buddy/buddy-auth "2.2.0"]
                 [org.clojars.pntblnk/clj-ldap "0.0.16"]]
  :main ^:skip-aot env-logger.handler
  :aot [env-logger.handler
        clojure.tools.logging.impl]
  :target-path "target/%s"
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.3"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}
   :uberjar {:aot :all}})
