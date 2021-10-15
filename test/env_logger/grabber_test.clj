(ns env-logger.grabber-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [clj-http.fake :refer [with-fake-routes]]
            [cheshire.core :refer [generate-string]]
            [java-time :as t]
            [next.jdbc :as jdbc]
            [env-logger.grabber :refer :all])
  (:import java.time.ZonedDateTime
           java.util.Date))

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
    (is (= {:date #inst "2017-11-13T19:10:00.000000000-00:00"
            :temperature 2.0
            :cloudiness 8
            :wind-speed 5.0}
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
                     :srsDimension 2,
                     :gml:id "BsWfsElementP.1.1.1"},
                    :content
                    [{:tag :gml:pos, :attrs nil,
                      :content ["60.17802 24.78732"]}]}]}
                 {:tag :BsWfs:Time, :attrs nil,
                  :content ["2017-11-13T19:10:00Z"]}
                 {:tag :BsWfs:ParameterName, :attrs nil, :content ["t2m"]}
                 {:tag :BsWfs:ParameterValue, :attrs nil, :content ["2.0"]}]}]}
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
                     :srsDimension 2,
                     :gml:id "BsWfsElementP.1.1.2"},
                    :content
                    [{:tag :gml:pos,
                      :attrs nil,
                      :content ["60.17802 24.78732 "]}]}]}
                 {:tag :BsWfs:Time, :attrs nil,
                  :content ["2017-11-13T19:10:00Z"]}
                 {:tag :BsWfs:ParameterName, :attrs nil, :content ["n_man"]}
                 {:tag :BsWfs:ParameterValue, :attrs nil, :content ["8.0"]}]}]}
             {:tag :wfs:member,
              :attrs nil,
              :content
              [{:tag :BsWfs:BsWfsElement,
                :attrs {:gml:id "BsWfsElement.1.1.3"},
                :content
                [{:tag :BsWfs:Location,
                  :attrs nil,
                  :content
                  [{:tag :gml:Point,
                    :attrs
                    {:srsName "http://www.opengis.net/def/crs/EPSG/0/4258",
                     :srsDimension 2,
                     :gml:id "BsWfsElementP.1.1.3"},
                    :content
                    [{:tag :gml:pos,
                      :attrs nil,
                      :content ["60.17802 24.78732 "]}]}]}
                 {:tag :BsWfs:Time, :attrs nil,
                  :content ["2017-11-13T19:10:00Z"]}
                 {:tag :BsWfs:ParameterName, :attrs nil, :content ["ws_10min"]}
                 {:tag :BsWfs:ParameterValue, :attrs nil,
                  :content ["5.0"]}]}]}])))
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
    (with-redefs [t/zoned-date-time (fn []
                                      (ZonedDateTime/of 2020 12 28
                                                        9 3 50 0
                                                        (t/zone-id
                                                         "Europe/Helsinki")))]
      (is (= "2020-12-28T09:00"
             (first (s/split (str (t/local-date-time (calculate-start-time)))
                             #"\.")))))
    (with-redefs [t/zoned-date-time (fn []
                                      (ZonedDateTime/of 2020 12 28
                                                        9 9 50 0
                                                        (t/zone-id
                                                         "Europe/Helsinki")))]
      (is (= "2020-12-28T09:00"
             (first (s/split (str (t/local-date-time (calculate-start-time)))
                             #"\[")))))))

(deftest test-weather-data-extraction-wfs
  (testing "Tests FMI weather data (WFS) extraction"
    (with-fake-routes {
                       #"https:\/\/opendata\.fmi\.fi\/wfs\?(.+)"
                       (fn [_] {:status 200
                                :body "<?xml version=\"1.0\"
                                             encoding=\"UTF-8\"?>
<wfs:FeatureCollection
    timeStamp=\"2017-11-13T19:12:39Z\"
    numberReturned=\"3\"
    numberMatched=\"3\"
    xmlns:wfs=\"http://www.opengis.net/wfs/2.0\"
    xmlns:gml=\"http://www.opengis.net/gml/3.2\"
    xmlns:BsWfs=\"http://xml.fmi.fi/schema/wfs/2.0\"
    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
    xsi:schemaLocation=\"http://www.opengis.net/wfs/2.0
                         http://schemas.opengis.net/wfs/2.0/wfs.xsd
                         http://xml.fmi.fi/schema/wfs/2.0
                         http://xml.fmi.fi/schema/wfs/2.0/fmi_wfs_simplefeature.xsd\">
  <wfs:member>
    <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.1\">
      <BsWfs:Location>
        <gml:Point gml:id=\"BsWfsElementP.1.1.1\" srsDimension=\"2\"
                   srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">
          <gml:pos>60.17802 24.78732 </gml:pos>
        </gml:Point>
      </BsWfs:Location>
      <BsWfs:Time>2017-11-13T19:10:00Z</BsWfs:Time>
      <BsWfs:ParameterName>t2m</BsWfs:ParameterName>
      <BsWfs:ParameterValue>2.0</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
  <wfs:member>
    <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.2\">
      <BsWfs:Location>
        <gml:Point gml:id=\"BsWfsElementP.1.1.2\" srsDimension=\"2\"
                   srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">
          <gml:pos>60.17802 24.78732 </gml:pos>
        </gml:Point>
      </BsWfs:Location>
      <BsWfs:Time>2017-11-13T19:10:00Z</BsWfs:Time>
      <BsWfs:ParameterName>n_man</BsWfs:ParameterName>
      <BsWfs:ParameterValue>8.0</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
  <wfs:member>
    <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.3\">
      <BsWfs:Location>
        <gml:Point gml:id=\"BsWfsElementP.1.1.3\" srsDimension=\"2\"
                   srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">
          <gml:pos>60.17802 24.78732 </gml:pos>
        </gml:Point>
      </BsWfs:Location>
      <BsWfs:Time>2017-11-13T19:10:00Z</BsWfs:Time>
      <BsWfs:ParameterName>ws_10min</BsWfs:ParameterName>
      <BsWfs:ParameterValue>5.0</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
</wfs:FeatureCollection>"})}
      (is (= {:date #inst "2017-11-13T19:10:00.000000000-00:00"
              :temperature 2.0
              :cloudiness 8
              :wind-speed 5.0}
             (-get-fmi-weather-data-wfs 87874))))
    (with-fake-routes {
                       #"https://opendata.fmi.fi/wfs\?.+"
                       (fn [req] {:status 200
                                  :body "not XML content"})}
      (is (nil? (-get-fmi-weather-data-wfs 87874))))
    (with-fake-routes {
                       #"https://opendata.fmi.fi/wfs\?.+"
                       (fn [req] {:status 400})}
      (is (nil? (-get-fmi-weather-data-wfs 87874))))))

(deftest test-weather-data-extraction-json
  (testing "Tests FMI weather data (JSON) extraction"
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 403})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body "Invalid JSON"})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body (generate-string
                                       {"generated" 1539719550869,
                                        "latestObservationTime" 1539719400000,
                                        "timeZoneId" "Europe/Helsinki",
                                        "TotalCloudCover" []})})}
      (is (nil? (-get-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body
                                (generate-string
                                 {"generated" 1539719550869
                                  "latestObservationTime" 1539719400000
                                  "timeZoneId" "Europe/Helsinki"
                                  "t2m" [[1539208800000 9.0]
                                         [1539212400000 11.0]]
                                  "TotalCloudCover" [[1539208800000 0]
                                                     [1539212400000 2]]
                                  "WindSpeedMS" [[1539208800000 5]
                                                 [1539212400000 6]]})})}
      (is (= {:date (t/sql-timestamp (t/zoned-date-time
                                      (str (.toInstant (new Date
                                                            1539719400000)))))
              :temperature 11.0
              :cloudiness 2
              :wind-speed 6}
             (-get-fmi-weather-data-json 87874))))))

(deftest test-weather-data-extraction
  (testing "Tests FMI weather data (JSON and WFS) extraction"
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body
                                (generate-string
                                 {"generated" 1539719550869
                                  "latestObservationTime" 1539719400000
                                  "timeZoneId" "Europe/Helsinki"
                                  "t2m" [[1539208800000 9.0]
                                         [1539212400000 11.0]]
                                  "TotalCloudCover" [[1539208800000 0]
                                                     [1539212400000 2]]
                                  "WindSpeedMS" [[1539208800000 5]
                                                 [1539212400000 6]]})})}
      (is (= {:date (t/sql-timestamp (t/zoned-date-time
                                      (str (.toInstant (new Date
                                                            1539719400000)))))
              :temperature 11.0
              :cloudiness 2
              :wind-speed 6}
             (get-fmi-weather-data 87874)))
      (with-fake-routes {
                         #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                         (fn [_] {:status 403})
                         #"https:\/\/opendata\.fmi\.fi\/wfs\?(.+)"
                         (fn [_] {:status 200
                                  :body "<?xml version=\"1.0\"
                                             encoding=\"UTF-8\"?>
<wfs:FeatureCollection
    timeStamp=\"2017-11-13T19:12:39Z\"
    numberReturned=\"3\"
    numberMatched=\"3\"
    xmlns:wfs=\"http://www.opengis.net/wfs/2.0\"
    xmlns:gml=\"http://www.opengis.net/gml/3.2\"
    xmlns:BsWfs=\"http://xml.fmi.fi/schema/wfs/2.0\"
    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
    xsi:schemaLocation=\"http://www.opengis.net/wfs/2.0
                         http://schemas.opengis.net/wfs/2.0/wfs.xsd
                         http://xml.fmi.fi/schema/wfs/2.0
                         http://xml.fmi.fi/schema/wfs/2.0/fmi_wfs_simplefeature.xsd\">
  <wfs:member>
    <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.1\">
      <BsWfs:Location>
        <gml:Point gml:id=\"BsWfsElementP.1.1.1\" srsDimension=\"2\"
                   srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">
          <gml:pos>60.17802 24.78732 </gml:pos>
        </gml:Point>
      </BsWfs:Location>
      <BsWfs:Time>2017-11-13T19:10:00Z</BsWfs:Time>
      <BsWfs:ParameterName>t2m</BsWfs:ParameterName>
      <BsWfs:ParameterValue>2.0</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
  <wfs:member>
    <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.2\">
      <BsWfs:Location>
        <gml:Point gml:id=\"BsWfsElementP.1.1.2\" srsDimension=\"2\"
                   srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">
          <gml:pos>60.17802 24.78732 </gml:pos>
        </gml:Point>
      </BsWfs:Location>
      <BsWfs:Time>2017-11-13T19:10:00Z</BsWfs:Time>
      <BsWfs:ParameterName>n_man</BsWfs:ParameterName>
      <BsWfs:ParameterValue>8.0</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
  <wfs:member>
    <BsWfs:BsWfsElement gml:id=\"BsWfsElement.1.1.3\">
      <BsWfs:Location>
        <gml:Point gml:id=\"BsWfsElementP.1.1.3\" srsDimension=\"2\"
                   srsName=\"http://www.opengis.net/def/crs/EPSG/0/4258\">
          <gml:pos>60.17802 24.78732 </gml:pos>
        </gml:Point>
      </BsWfs:Location>
      <BsWfs:Time>2017-11-13T19:10:00Z</BsWfs:Time>
      <BsWfs:ParameterName>ws_10min</BsWfs:ParameterName>
      <BsWfs:ParameterValue>5.0</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
</wfs:FeatureCollection>"})}
        (is (= {:date #inst "2017-11-13T19:10:00.000000000-00:00"
                :temperature 2.0
                :cloudiness 8
                :wind-speed 5.0}
               (get-fmi-weather-data 87874))))
      (with-fake-routes {
                         #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                         (fn [_] {:status 403})
                         #"https:\/\/opendata\.fmi\.fi\/wfs\?(.+)"
                         (fn [_] {:status 404})}
        (is (nil? (get-fmi-weather-data 87874)))))))

(deftest weather-query-ok
  (testing "Test when it is OK to query for FMI weather data"
    (with-redefs [jdbc/execute-one! (fn [con query opts] '())]
      (is (true? (weather-query-ok? {} 5))))
    (with-redefs [jdbc/execute-one! (fn [con query opts]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 3))})]
      (is (false? (weather-query-ok? {} 5))))
    (with-redefs [jdbc/execute-one! (fn [con query opts]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 3))})]
      (is (true? (weather-query-ok? {} 3))))
    (with-redefs [jdbc/execute-one! (fn [con query opts]
                                      {:recorded (t/minus (t/offset-date-time)
                                                          (t/minutes 6))})]
      (is (true? (weather-query-ok? {} 5))))))
