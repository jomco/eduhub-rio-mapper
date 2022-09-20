(ns nl.surf.eduhub-rio-mapper.rio.mutator
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [when-result]]
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

(defn make-mutator
  [{:keys [root-url sender-oin recipient-oin credentials]}]
  (let [datamap (make-datamap sender-oin recipient-oin)]
    (fn upsert [{:keys [action rio-sexp]}]
      (when-result [xml (soap/prepare-soap-call action [rio-sexp] datamap credentials)]
       (xml-utils/post-body (str root-url "beheren4.0")
                            xml
                            datamap
                            action
                            credentials)))))
