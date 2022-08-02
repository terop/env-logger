(ns env-logger.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [env-logger.render :refer [serve-as
                                       serve-json
                                       serve-template
                                       serve-text]]))
(deftest serve-as-test
  (testing "serve-as function"
    (let [resp (serve-as "Hello" "text/plain")]
      (is (= 200 (:status resp)))
      (is (= {"Content-Type" "text/plain"} (:headers resp)))
      (is (= "Hello" (:body resp))))))

(deftest serve-json-test
  (testing "serve-json function"
    (let [resp (serve-json {:hello "World"})]
      (is (= 200 (:status resp)))
      (is (= {"Content-Type" "application/json"} (:headers resp)))
      (is (= "{\"hello\":\"World\"}" (:body resp))))))

(deftest serve-template-test
  (testing "serve-template function"
    (let [resp (serve-template "templates/login.html" {})]
      (is (= 200 (:status resp)))
      (is (= {"Content-Type" "text/html;charset=utf-8"} (:headers resp)))
      (is (> (count (:body resp)) 100)))))

(deftest serve-text-test
  (testing "serve-text function"
    (let [resp (serve-text "Hello")]
      (is (= 200 (:status resp)))
      (is (= {"Content-Type" "text/plain"} (:headers resp)))
      (is (= "Hello" (:body resp))))))
