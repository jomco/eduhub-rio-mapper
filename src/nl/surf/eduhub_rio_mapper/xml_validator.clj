(ns nl.surf.eduhub-rio-mapper.xml-validator
  (:import (java.io File StringReader)
           (javax.xml XMLConstants)
           (javax.xml.validation SchemaFactory)
           (javax.xml.transform.stream StreamSource)
           (org.xml.sax SAXException)))

(defn create-validation-fn [^String schema]
  (let [^File file (File. schema)
        ^StreamSource source (StreamSource. file)
        validator (-> (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                      (.newSchema source)
                      (.newValidator))]
    (fn [xmldoc]
      (try
        (.validate validator (StreamSource. (StringReader. xmldoc)))
        true
        (catch SAXException _ false)))))