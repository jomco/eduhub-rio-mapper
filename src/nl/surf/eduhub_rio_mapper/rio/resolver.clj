(ns nl.surf.eduhub-rio-mapper.rio.resolver
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

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

;; TODO: resolver should just return the opleidingscode when there are
;; no errors.
(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an education-specification id and returns a map
  with the corresponding RIO opleidingscode, or errors."
  [credentials]
  (fn resolver
    [ooapi-id]
    (if (nil? ooapi-id)
      nil
      (let [action "opvragen_rioIdentificatiecode"
            xml (soap/prepare-soap-call action
                                        [[:duo:eigenOpleidingseenheidSleutel ooapi-id]]
                                        soap/raadplegen
                                        credentials)]
        (when (errors? xml)
          (throw (ex-info "Error preparing resolve" xml)))
        (-> (xml-utils/post-body (:dev-url soap/raadplegen) xml soap/raadplegen action credentials)
            (xml-utils/xml->dom)
            (.getDocumentElement)
            (xml-utils/get-in-dom,, ["SOAP-ENV:Body" "ns2:opvragen_rioIdentificatiecode_response"])
            (handle-rio-response))))))
