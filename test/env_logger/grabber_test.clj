(ns env-logger.grabber-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as j]
            [clj-http.fake :refer [with-fake-routes]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [cheshire.core :refer [generate-string]]
            [env-logger.grabber :refer :all])
  (:import org.joda.time.DateTime
           org.joda.time.DateTimeZone))

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
    (is (= {:date "2017-11-13T19:10:00Z"
            :temperature 2.0
            :cloudiness 8
            :pressure 1006.5}
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
                 {:tag :BsWfs:ParameterName, :attrs nil, :content ["p_sea"]}
                 {:tag :BsWfs:ParameterValue, :attrs nil,
                  :content ["1006.5"]}]}]}])))
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

(deftest test-weather-data-extraction-wfs
  (testing "Tests FMI weather data (WFS) extraction"
    (with-fake-routes {
                       #"https:\/\/data\.fmi\.fi\/fmi-apikey\/(.+)"
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
      <BsWfs:ParameterName>p_sea</BsWfs:ParameterName>
      <BsWfs:ParameterValue>1006.5</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
</wfs:FeatureCollection>"})}
      (is (= {:date "2017-11-13T19:10:00Z"
              :temperature 2.0
              :cloudiness 8
              :pressure 1006.5}
             (-get-latest-fmi-weather-data-wfs "api-key" 87874))))
    (with-fake-routes {
                       #"https://data.fmi.fi/fmi-apikey/.+"
                       (fn [req] {:status 200
                                  :body "not XML content"})}
      (is (nil? (-get-latest-fmi-weather-data-wfs "my-api-key" 87874))))
    (with-fake-routes {
                       #"https://data.fmi.fi/fmi-apikey/.+"
                       (fn [req] {:status 400})}
      (is (nil? (-get-latest-fmi-weather-data-wfs "my-api-key" 87874))))))

(deftest test-weather-data-extraction-json
  (testing "Tests FMI weather data (JSON) extraction"
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 403})}
      (is (nil? (-get-latest-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body "Invalid JSON"})}
      (is (nil? (-get-latest-fmi-weather-data-json 87874))))
    (with-fake-routes {
                       #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                       (fn [_] {:status 200
                                :body (generate-string
                                       {"generated" 1539719550869,
                                        "latestObservationTime" 1539719400000,
                                        "timeZoneId" "Europe/Helsinki",
                                        "TotalCloudCover" []})})}
      (is (nil? (-get-latest-fmi-weather-data-json 87874))))
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
                                  "Pressure" [[1539208800000 1024.8]
                                              [1539212400000 1026.0]]})})}
      (is (= {:date "2018-10-16T16:50:00Z"
              :temperature 11.0
              :cloudiness 2
              :pressure 1026.0}
             (-get-latest-fmi-weather-data-json 87874))))))

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
                                  "Pressure" [[1539208800000 1024.8]
                                              [1539212400000 1026.0]]})})}
      (is (= {:date "2018-10-16T16:50:00Z"
              :temperature 11.0
              :cloudiness 2
              :pressure 1026.0}
             (get-latest-fmi-weather-data "api-key" 87874)))
      (with-fake-routes {
                         #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                         (fn [_] {:status 403})
                         #"https:\/\/data\.fmi\.fi\/fmi-apikey\/(.+)"
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
      <BsWfs:ParameterName>p_sea</BsWfs:ParameterName>
      <BsWfs:ParameterValue>1006.5</BsWfs:ParameterValue>
    </BsWfs:BsWfsElement>
  </wfs:member>
</wfs:FeatureCollection>"})}
        (is (= {:date "2017-11-13T19:10:00Z"
                :temperature 2.0
                :cloudiness 8
                :pressure 1006.5}
               (get-latest-fmi-weather-data "api-key" 87874))))
      (with-fake-routes {
                         #"https:\/\/ilmatieteenlaitos.fi\/observation-data(.+)"
                         (fn [_] {:status 403})
                         #"https:\/\/data\.fmi\.fi\/fmi-apikey\/(.+)"
                         (fn [_] {:status 404})}
        (is (nil? (get-latest-fmi-weather-data "api-key" 87874)))))))

(deftest weather-query-ok
  (testing "Test when it is OK to query for FMI weather data"
    (with-redefs [j/query (fn [db query] '())]
      (is (true? (weather-query-ok? {} 5))))
    (with-redefs [j/query (fn [db query]
                            (list {:recorded (t/minus (t/now)
                                                      (t/minutes 3))}))]
      (is (false? (weather-query-ok? {} 5))))
    (with-redefs [j/query (fn [db query]
                            (list {:recorded (t/minus (t/now)
                                                      (t/minutes 3))}))]
      (is (false? (weather-query-ok? {} 3))))
    (with-redefs [j/query (fn [db query]
                            (list {:recorded (t/minus (t/now)
                                                      (t/minutes 6))}))]
      (is (true? (weather-query-ok? {} 5))))))
