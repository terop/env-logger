(ns env-logger.grabber-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as j]
            [clj-http.fake :refer [with-fake-routes]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [env-logger.db-test :refer [test-postgres]]
            [env-logger.grabber :refer :all])
  (:import org.joda.time.DateTime
           org.joda.time.DateTimeZone
           java.util.concurrent.TimeUnit))

(deftest test-parse-xml
  (testing "XML parser tests"
    (is (= {:tag :person, :attrs nil,
            :content [{:tag :name, :attrs nil, :content ["John"]}
                      {:tag :lastname, :attrs nil, :content ["Doe"]}]}
           (parse-xml (str "<person><name>John</name>"
                           "<lastname>Doe</lastname></person>"))))
    (is (thrown? org.xml.sax.SAXParseException
                 (parse-xml (str "<person><name>John</name>"
                                 "<lastname>Doe</lastname>"))))))

(deftest test-data-extraction
  (testing "Data extraction function tests"
    (is (= {:date "2016-08-12T05:20:00Z", :temperature 12.0, :cloudiness 0}
           (extract-data
            [{:tag :wfs:member,
              :attrs nil,
              :content
              [{:tag :BsWfs:BsWfsElement,
                :attrs {:gml:id "BsWfsElement.1.1.1"},
                :content
                [{:tag :BsWfs:Location,
                  :attrs nil,
                  :content
                  [{:tag :gml:Point,
                    :attrs
                    {:srsName "http://www.opengis.net/def/crs/EPSG/0/4258",
                     :srsDimension "2",
                     :gml:id "BsWfsElementP.1.1.1"},
                    :content
                    [{:tag :gml:pos,
                      :attrs nil,
                      :content ["60.17802 24.78732 "]}]}]}
                 {:tag :BsWfs:Time, :attrs nil,
                  :content ["2016-08-12T05:20:00Z"]}
                 {:tag :BsWfs:ParameterName, :attrs nil, :content ["t2m"]}
                 {:tag :BsWfs:ParameterValue, :attrs nil, :content ["12.0"]}]}]}
             {:tag :wfs:member,
              :attrs nil,
              :content
              [{:tag :BsWfs:BsWfsElement,
                :attrs {:gml:id "BsWfsElement.1.1.2"},
                :content
                [{:tag :BsWfs:Location,
                  :attrs nil,
                  :content
                  [{:tag :gml:Point,
                    :attrs
                    {:srsName "http://www.opengis.net/def/crs/EPSG/0/4258",
                     :srsDimension "2",
                     :gml:id "BsWfsElementP.1.1.2"},
                    :content
                    [{:tag :gml:pos,
                      :attrs nil,
                      :content ["60.17802 24.78732 "]}]}]}
                 {:tag :BsWfs:Time, :attrs nil,
                  :content ["2016-08-12T05:20:00Z"]}
                 {:tag :BsWfs:ParameterName, :attrs nil, :content ["n_man"]}
                 {:tag :BsWfs:ParameterValue, :attrs nil,
                  :content ["0.0"]}]}]}])))
    (is (nil? (extract-data
               [{:tag :wfs:member,
                 :attrs nil,
                 :content
                 [{:tag :BsWfs:BsWfsElement,
                   :attrs {:gml:id "BsWfsElement.1.1.1"},
                   :content
                   [{:tag :BsWfs:Location,
                     :attrs nil,
                     :content
                     [{:tag :gml:Point,
                       :attrs
                       {:srsName "http://www.opengis.net/def/crs/EPSG/0/4258",
                        :srsDimension "2",
                        :gml:id "BsWfsElementP.1.1.1"},
                       :content
                       [{:tag :gml:pos,
                         :attrs nil,
                         :content ["60.17802 24.78732 "]}]}]}
                    {:tag :BsWfs:Time, :attrs nil,
                     :content ["2016-08-12T05:20:00Z"]}
                    {:tag :BsWfs:ParameterName, :attrs nil, :content ["t2m"]}
                    {:tag :BsWfs:ParameterValue, :attrs nil,
                     :content ["12.0"]}]}]}])))))

(deftest test-start-time-calculation
  (testing "Tests the start time calculation"
    (with-redefs [t/now (fn [] (new DateTime 2016 8 13 5 27
                                    DateTimeZone/UTC))]
      (is (= "2016-08-13T05:20:00Z" (f/unparse (f/formatters :date-time-no-ms)
                                               (calculate-start-time)))))
    (with-redefs [t/now (fn [] (new DateTime 2016 8 13 5 20
                                    DateTimeZone/UTC))]
      (is (= "2016-08-13T05:20:00Z" (f/unparse (f/formatters :date-time-no-ms)
                                               (calculate-start-time)))))
    (with-redefs [t/now (fn [] (new DateTime 2016 8 13 5 29 59
                                    DateTimeZone/UTC))]
      (is (= "2016-08-13T05:20:00Z" (f/unparse (f/formatters :date-time-no-ms)
                                               (calculate-start-time)))))))

(deftest test-latest-data-extraction
  (testing "Tests FMI data extraction"
    (with-fake-routes {
                       #"http:\/\/data\.fmi\.fi\/fmi-apikey\/(.+)"
                       (fn [_] {:status 200
                                :body "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wfs:FeatureCollection\n  timeStamp=\"2016-08-12T17:10:45Z\"\n  numberReturned=\"2\"\n  numberMatched=\"2\"\n      xmlns:wfs=\"http://www.opengis.net/wfs/2.0\"\n    xmlns:gml=\"http://www.opengis.net/gml/3.2\"\n    xmlns:BsWfs=\"http://xml.fmi.fi/schema/wfs/2.0\"\n    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n    xsi:schemaLocation=\"http://www.opengis.net/wfs/2.0 http://schemas.opengis.net/wfs/2.0/wfs.xsd\n                        http://xml.fmi.fi/schema/wfs/2.0 http://xml.fmi.fi/schema/wfs/2.0/fmi_wfs_simplefeature.xsd\"\n>\n  \t\n\t<wfs:member>\n            <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.1\">\n                <BsWfs:Location>\n                    <gml:Point gml:id=\"BsWfsElementP.1.1.1\" srsDimension=\"2\" srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">\n                        <gml:pos>60.17802 24.78732 </gml:pos>\n                    </gml:Point>\n                </BsWfs:Location>\n                <BsWfs:Time>2016-08-12T17:10:00Z</BsWfs:Time>\n                <BsWfs:ParameterName>t2m</BsWfs:ParameterName>\n                <BsWfs:ParameterValue>15</BsWfs:ParameterValue>\n            </BsWfs:BsWfsElement>\n\t</wfs:member>\n\t\n\t<wfs:member>\n            <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.2\">\n                <BsWfs:Location>\n                    <gml:Point gml:id=\"BsWfsElementP.1.1.2\" srsDimension=\"2\" srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">\n                        <gml:pos>60.17802 24.78732 </gml:pos>\n                    </gml:Point>\n                </BsWfs:Location>\n                <BsWfs:Time>2016-08-12T17:10:00Z</BsWfs:Time>\n                <BsWfs:ParameterName>n_man</BsWfs:ParameterName>\n                <BsWfs:ParameterValue>0.0</BsWfs:ParameterValue>\n            </BsWfs:BsWfsElement>\n\t</wfs:member>\n\t\n\n</wfs:FeatureCollection>\n"})}
      (is (= {:date "2016-08-12T17:10:00Z", :temperature 15.0, :cloudiness 0}
             (get-latest-fmi-data "api-key" 87874))))
    (with-fake-routes {
                       #"http://data.fmi.fi/fmi-apikey/.+"
                       (fn [req] {:status 200
                                  :body "not XML content"})}
      (is (= {} (get-latest-fmi-data "my-api-key" 87874))))
    (with-fake-routes {
                       #"http://data.fmi.fi/fmi-apikey/.+"
                       (fn [req] {:status 400})}
      (is (= {} (get-latest-fmi-data "my-api-key" 87874))))))

(deftest weather-query-ok
  (testing "Test when it is OK to query for FMI weather observations"
    (let [offset-millisec (.getOffset (t/default-time-zone)
                                      (.getMillis (DateTime/now)))
          hours (.toHours (TimeUnit/MILLISECONDS) offset-millisec)]
      ;; Timestamps are recorded in local time
      ;; Dummy test which kind of works, needs to be fixed properly at some time
      (is (true? (weather-query-ok? test-postgres (* hours 50))))
      (with-redefs [j/query (fn [db query] '())]
        (is (true? (weather-query-ok? test-postgres (* hours 50))))))))
