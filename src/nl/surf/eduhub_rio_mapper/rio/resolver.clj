(ns nl.surf.eduhub-rio-mapper.rio.resolver
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator]))

(defn- single-xml-unwrapper [element tag]
  (-> element
      (xml-utils/get-in-dom [tag])
      (.getFirstChild)
      (.getTextContent)))

(defn- handle-rio-response [element]
  (let [goedgekeurd (single-xml-unwrapper element "ns2:requestGoedgekeurd")]
    (if (= "true" goedgekeurd)
      {:code (single-xml-unwrapper element "ns2:opleidingseenheidcode")}
      {:errors (.getTextContent (.getFirstChild (xml-utils/get-in-dom element ["ns2:foutmelding" "ns2:fouttekst"])))})))


(def schema "http://duo.nl/schema/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def contract "http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def validator  (xml-validator/create-validation-fn "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd"))

;; TODO: Public for testing only -- we should get rid of 'datamap'
(defn make-datamap
  [sender-oin recipient-oin]
  {:schema   schema
   :contract contract
   :validator validator
   :to-url    (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

;; TODO: resolver should just return the opleidingscode when there are
;; no errors.

(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an education-specification id and returns a map
  with the corresponding RIO opleidingscode, or errors."
  [{:keys [root-url credentials sender-oin recipient-oin]}]
  (let [datamap (make-datamap sender-oin recipient-oin)]
    (fn resolver
      [ooapi-id]
      (if (nil? ooapi-id)
        nil
        (let [
              action "opvragen_rioIdentificatiecode"
              xml    (soap/prepare-soap-call action
                                             [[:duo:eigenOpleidingseenheidSleutel ooapi-id]]
                                             datamap
                                             credentials)]
          (when (errors? xml)
            (throw (ex-info "Error preparing resolve" xml)))
          (-> (xml-utils/post-body (str root-url "raadplegen4.0")
                                   xml datamap action credentials)
              (xml-utils/xml->dom)
              (.getDocumentElement)
              (xml-utils/get-in-dom,, ["SOAP-ENV:Body" "ns2:opvragen_rioIdentificatiecode_response"])
              (handle-rio-response)))))))
