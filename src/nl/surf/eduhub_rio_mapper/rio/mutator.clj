(ns nl.surf.eduhub-rio-mapper.rio.mutator
  (:require [clojure.data.xml :as clj-xml]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.errors :refer [guard-errors]]
            [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(def schema
  "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4")

(def contract
  "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4")

(def validator
  (xml-validator/create-validation-fn "DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd"))

;; TODO: get rid of datamap
(defn make-datamap
  [sender-oin recipient-oin]
  (assert [sender-oin recipient-oin])
  {:schema    schema
   :contract  contract
   :validator validator
   :to-url    (str "https://duo.nl/RIO/services/beheren4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn- handle-rio-mutate-response [^Element element]
  {:pre [(some? element)]}
  (if (loader/goedgekeurd? element)
    (-> element
        xml-utils/dom->xml
        clj-xml/parse-str
        xml-utils/xml-event-tree->edn)
    {:errors ["Mutation not approved."]}))

;; Returns function that returns either a hashmap with an errors key, or a parsed xml fragment of response, or throws exception.
(defn make-mutator
  [{:keys [root-url recipient-oin credentials]} request-poster]
  {:pre [(some? (:certificate credentials))]}
  (fn mutator [{:keys [action sender-oin rio-sexp]}]
    {:pre [(vector? (first rio-sexp))]}
    (let [datamap (make-datamap sender-oin recipient-oin)
          xml-or-errors (soap/prepare-soap-call action rio-sexp datamap credentials)
          response-element-name (str "ns2:" action "_response")
          url (str root-url "beheren4.0")]
      (when-let [xml (guard-errors xml-or-errors (str "Error preparing " action))]
        (let [{:keys [success body status]} (request-poster url xml contract action credentials)]
          (if success
            (-> body
                (loader/assert-mutator-response)
                (xml-utils/xml->dom)
                (.getDocumentElement)
                (xml-utils/get-in-dom ["SOAP-ENV:Body" response-element-name])
                (handle-rio-mutate-response))
            {:errors [(format "HTTP call unsuccessful; status %s" status)], :http-status status}))))))
