(ns env-logger.render
  "Namespace for various content rendering functions"
  (:require [jsonista.core :as j]
            [ring.util.response :as resp]
            [selmer.parser :refer [render-file]]))

(defn serve-as
  "Serves the given content with the provided Content-Type header."
  [content content-type]
  (resp/content-type
   (resp/response content) content-type))

(defn serve-json
  "Serves the given content as JSON with the application/json Content-Type."
  [content]
  (serve-as (j/write-value-as-string content)
            "application/json"))

(defn serve-template
  "Serves the given template and values with the text/html Content-Type."
  [template values]
  (serve-as (render-file template values)
            "text/html;charset=utf-8"))

(defn serve-text
  "Serves the given content as text with the text/plain Content-Type."
  [content]
  (serve-as content "text/plain"))
