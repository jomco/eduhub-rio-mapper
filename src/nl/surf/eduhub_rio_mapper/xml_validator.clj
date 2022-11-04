(ns nl.surf.eduhub-rio-mapper.xml-validator
  (:require [clojure.java.io :as io])
  (:import java.io.StringReader
           javax.xml.XMLConstants
           javax.xml.transform.stream.StreamSource
           javax.xml.validation.SchemaFactory
           org.xml.sax.SAXException))

(defn create-problems-fn
  [^String schema-path]
  (let [validator (-> (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                      (.newSchema (-> schema-path
                                      io/resource
                                      io/input-stream
                                      StreamSource.))
                      .newValidator)]
    (fn problems
      [^String xmldoc]
      (try
        (->> xmldoc StringReader. StreamSource. (.validate validator))
        nil
        (catch SAXException ex
          ex)))))

(defn create-validation-fn
  "Creates an XML validator function with given schema-path.
  The function expects an XML string and throws an error if it does
  not conform to the schema."
  [^String schema-path]
  (let [problems (create-problems-fn schema-path)]
    (fn validation
      [^String xmldoc]
      (when-let [ex (problems xmldoc)]
        (throw (ex-info "XSD validation error in document"
                        {:message (.getMessage ex)
                         :doc     xmldoc})))
      xmldoc)))
