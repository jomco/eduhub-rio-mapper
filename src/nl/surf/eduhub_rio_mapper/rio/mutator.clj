(ns nl.surf.eduhub-rio-mapper.rio.mutator
  (:require
    [clojure.data.json :as json]
    [clojure.data.xml :as clj-xml]
    [nl.surf.eduhub-rio-mapper.errors :refer [guard-errors]]
    [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator]))

(def schema
  "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4")

(def contract
  "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4")

(def validator
  (xml-validator/create-validation-fn "DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd"))

;; TODO: get rid of datamap
(defn make-datamap
  [sender-oin recipient-oin]
  {:schema    schema
   :contract  contract
   :validator validator
   :to-url    (str "https://duo.nl/RIO/services/beheren4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn- handle-rio-mutate-response [element]
  (when (loader/goedgekeurd? element)
    (-> element
        xml-utils/dom->xml
        clj-xml/parse-str
        xml-utils/xml-event-tree->edn
        json/write-str)))

(defn make-mutator
  [{:keys [root-url sender-oin recipient-oin credentials]}]
  (let [datamap (make-datamap sender-oin recipient-oin)]
    (fn [{:keys [action rio-sexp]}]
      (let [xml-or-errors (soap/prepare-soap-call action [rio-sexp] datamap credentials)
            response-element-name (str "ns2:" action "_response")
            url (str root-url "beheren4.0")]
        (when-let [xml (guard-errors xml-or-errors (str "Error preparing " action))]
          (-> (xml-utils/post-body url xml contract action credentials)
              (xml-utils/xml->dom)
              (.getDocumentElement)
              (xml-utils/get-in-dom ["SOAP-ENV:Body" response-element-name])
              (handle-rio-mutate-response)))))))
