(ns nl.surf.eduhub-rio-mapper.rio.loader
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(defn- single-xml-unwrapper [element tag]
  (-> element
      (xml-utils/get-in-dom [tag])
      (.getFirstChild)
      (.getTextContent)))

(defn goedgekeurd? [^Element element]
  (= "true" (single-xml-unwrapper element "ns2:requestGoedgekeurd")))

(defn- handle-rio-resolver-response [^Element element]
  (if (goedgekeurd? element)
    {:code (single-xml-unwrapper element "ns2:opleidingseenheidcode")}
    (do
      (log/debug (format "Response not approved; %s" (-> element xml-utils/element->edn pr-str)))
      {:errors (-> element
                   (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:fouttekst"])
                   (.getFirstChild)
                   (.getTextContent))})))

(defn- handle-rio-getter-response [^Element element]
  (when (goedgekeurd? element)
    (-> element xml-utils/element->edn json/write-str)))

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

(defn assert-mutator-response
  [{:keys [success body status]}]
  (when-not success
    (throw (ex-info "Invalid resolver http status"
                    {:body body, :status status})))
  body)

(defn- extract-getter-response
  [{:keys [success body status]} response-element-name]
  (when-not success
    (throw (ex-info "Invalid getter http status"
                    {:body body, :status status})))

  (when-not (re-find (re-pattern response-element-name) body)
    (throw (ex-info "Invalid getter response"
                    {:body body})))
  body)

(defn- extract-resolver-response
  [{:keys [success body status]}]
  (when-not success
    (throw (ex-info "Invalid resolver http status"
                    {:body body, :status status})))
  (when-not (re-find #"ns2:opvragen_rioIdentificatiecode_response" body)
    (throw (ex-info "Invalid resolver response"
                    {:body body})))
  body)

;; TODO: resolver should just return the opleidingscode when there are
;; no errors.

(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an `education-specification-id` and an
  `institution-oin` and returns a map with the corresponding RIO
  opleidingscode, or errors."
  [{:keys [root-url credentials recipient-oin]}]
  (fn resolver
    [education-specification-id institution-oin]
    {:pre [institution-oin]}
    (let [datamap (make-datamap institution-oin recipient-oin)]
      (when (some? education-specification-id)
        (let [action  "opvragen_rioIdentificatiecode"
              url     (str root-url "raadplegen4.0")
              headers {"SOAPAction" (str contract "/" action)}
              xml     (soap/prepare-soap-call action
                                              [[:duo:eigenOpleidingseenheidSleutel education-specification-id]]
                                              datamap
                                              credentials)]
          (when (errors? xml)
            (log/debug (format "Errors in soap/prepare-soap-call for action %s and eduspec-id %s; %s" action education-specification-id (pr-str xml)))
            (throw (ex-info "Error preparing resolve" xml)))
          (-> {:url          url
               :method       :post
               :body         xml
               :headers      headers
               :content-type :xml}
              (merge credentials)
              http-utils/send-http-request
              extract-resolver-response
              xml-utils/xml->dom
              .getDocumentElement
              (xml-utils/get-in-dom ["SOAP-ENV:Body" "ns2:opvragen_rioIdentificatiecode_response"])
              (handle-rio-resolver-response)))))))

(defn execute-opvragen [root-url xml contract credentials type]
  (let [action                (str "opvragen_" type)
        response-element-name (str "ns2:opvragen_" type "_response")]
    (assert (not (errors? xml)) "unexpected error in request body")
    (let [url     (str root-url "raadplegen4.0")
          headers {"SOAPAction" (str contract "/" action)}]
      (-> {:url          url
           :method       :post
           :body         xml
           :headers      headers
           :content-type :xml}
          (merge credentials)
          (http-utils/send-http-request)
          (extract-getter-response response-element-name)
          (xml-utils/xml->dom)
          (.getDocumentElement)
          (xml-utils/get-in-dom ["SOAP-ENV:Body" response-element-name])
          (handle-rio-getter-response)))))

(def TODO-onderwijsaanbiedercode "110A133") ; TODO replace by id

(defn- valid-onderwijsbestuurcode? [code]
  (re-matches #"\d\d\dB\d\d\d" code))

(defn make-getter
  "Return a function that looks up an 'aangeboden opleiding' by id.

  The getter takes an program or course id and returns a map of
  data with the RIO attributes, or errors."
  [{:keys [root-url credentials recipient-oin]}]
  (fn getter [institution-oin type id & [pagina]]
    (let [datamap (make-datamap institution-oin recipient-oin)]
      (when (some? id)
        (let [soap-caller (fn prepare-soap [rio-sexp]
                            (soap/prepare-soap-call (str "opvragen_" type) rio-sexp datamap credentials))]
          (case type
            "opleidingseenhedenVanOrganisatie"
            (let [onderwijsbestuurcode id
                  rio-sexp             [[:duo:onderwijsbestuurcode onderwijsbestuurcode]
                                        [:duo:pagina (or pagina 0)]]]
              (if (valid-onderwijsbestuurcode? onderwijsbestuurcode)
                (execute-opvragen root-url (soap-caller rio-sexp) (:contract datamap) credentials type)
                (let [error-msg (format "onderwijsbestuurcode %s has invalid format" onderwijsbestuurcode)]
                  (log/debug error-msg)
                  {:errors (format "onderwijsbestuurcode %s has invalid format" onderwijsbestuurcode)})))

            "aangebodenOpleidingenVanOrganisatie"
            (let [rio-sexp [[:duo:onderwijsaanbiedercode TODO-onderwijsaanbiedercode]
                            [:duo:pagina (or pagina 0)]]]
              (execute-opvragen root-url (soap-caller rio-sexp) (:contract datamap) credentials type))

            "aangebodenOpleiding"
            (let [rio-sexp [[:duo:aangebodenOpleidingCode id]]]
              (assert (nil? pagina) "unexpected 'pagina' argument")
              (execute-opvragen root-url (soap-caller rio-sexp) (:contract datamap) credentials type))))))))
