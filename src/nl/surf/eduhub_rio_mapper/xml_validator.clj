(ns nl.surf.eduhub-rio-mapper.xml-validator
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import (java.io File StringReader)
           (javax.xml XMLConstants)
           (javax.xml.validation SchemaFactory)
           (javax.xml.transform.stream StreamSource)
           (org.xml.sax SAXException)))

(defn create-validation-fn [^String schema]
  (let [^File file (-> schema io/resource io/file)
        ^StreamSource source (StreamSource. file)
        validator (-> (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                      (.newSchema source)
                      .newValidator)]
    (fn [xmldoc]
      (try
        (->> xmldoc StringReader. StreamSource. (.validate validator))
        true
        (catch SAXException e
          (do
            (log/warn e)
            false))))))
