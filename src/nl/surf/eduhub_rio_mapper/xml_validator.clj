(ns nl.surf.eduhub-rio-mapper.xml-validator
  (:require [clojure.java.io :as io])
  (:import (java.io File StringReader)
           (javax.xml XMLConstants)
           (javax.xml.validation SchemaFactory)
           (javax.xml.transform.stream StreamSource)
           (org.xml.sax SAXException)))

(defn create-problems-fn
  [^String schema]
  (let [^File file (-> schema io/resource io/file)
        ^StreamSource source (StreamSource. file)
        validator (-> (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                      (.newSchema source)
                      .newValidator)]
    (fn problems
      [xmldoc]
      (try
        (->> xmldoc StringReader. StreamSource. (.validate validator))
        nil
        (catch SAXException e
          e)))))

(defn create-validation-fn
  [^String schema]
  (let [problems (create-problems-fn schema)]
    (fn validation
      [xmldoc]
      (if-let [p (problems xmldoc)]
        {:errors p}
        xmldoc))))
