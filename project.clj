(defproject env-logger "0.2.11-SNAPSHOT"
  :description "A simple data logger"
  :url "https://github.com/terop/env-logger"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/core.cache "1.0.225"]
                 [org.clojure/data.zip "1.0.0"]
                 [ring "1.9.4"]
                 [ring/ring-defaults "0.3.3"]
                 [ring/ring-json "0.5.1"]
                 [com.taoensso/timbre "5.1.2"]
                 [org.postgresql/postgresql "42.3.1"]
                 [com.github.seancorfield/next.jdbc "1.2.753"]
                 [com.github.seancorfield/honeysql "2.1.833"]
                 [clojure.java-time "0.3.3"]
                 [org.threeten/threeten-extra "1.7.0"]
                 [compojure "1.6.2"]
                 [cheshire "5.10.1"]
                 [selmer "1.12.45"]
                 [clj-http "3.12.3"]
                 [com.yubico/yubico-validation-client2 "3.1.0"]
                 [buddy/buddy-hashers "1.8.1"]
                 [buddy/buddy-auth "3.0.1"]
                 [buddy/buddy-sign "3.4.1"]
                 ;; Used by dependencies, not the app itself
                 [org.slf4j/slf4j-log4j12 "1.7.32"]]
  :main ^:skip-aot env-logger.handler
  :aot [env-logger.handler]
  :target-path "target/%s/"
  :profiles
  {:dev {:dependencies [[clj-http-fake "1.0.3"]]
         :resource-paths ["resources"]
         :env {:squiggly {:checkers [:eastwood :kibit :typed]}}}
   :uberjar {:aot :all}})
