(ns nl.surf.eduhub-rio-mapper.xml-validator
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
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
      [xmldoc]
      (try
        (->> xmldoc StringReader. StreamSource. (.validate validator))
        nil
        (catch SAXException ex
          ex)))))

(defn create-validation-fn
  [^String schema-path]
  (let [problems (create-problems-fn schema-path)]
    (fn validation
      [xmldoc]
      (if-let [ex (problems xmldoc)]
        (do
          (log/error ex (format "XSD validation erro in document:\n %s" xmldoc))
          {:errors {:message (.getMessage ex)}})
        xmldoc))))
