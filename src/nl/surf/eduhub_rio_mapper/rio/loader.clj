(ns nl.surf.eduhub-rio-mapper.rio.loader
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require
    [clojure.data.json :as json]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.Relation :as-alias Relation]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(def valid-get-actions #{"opleidingseenhedenVanOrganisatie" "aangebodenOpleidingenVanOrganisatie"
                         "opleidingsrelatiesBijOpleidingseenheid" "aangebodenOpleiding"})

(def schema "http://duo.nl/schema/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def contract "http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def validator  (xml-validator/create-validation-fn "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd"))

(defn- single-xml-unwrapper [element tag]
  (-> element
      (xml-utils/get-in-dom [tag])
      (.getFirstChild)
      (.getTextContent)))

(defn goedgekeurd? [^Element element]
  {:pre [element]}
  (= "true" (single-xml-unwrapper element "ns2:requestGoedgekeurd")))

(defn- handle-rio-resolver-response [^Element element]
  {:pre [element]}
  (if (goedgekeurd? element)
    {:code (single-xml-unwrapper element "ns2:opleidingseenheidcode")}
    (do
      (log/debug (format "Response not approved; %s" (-> element xml-utils/element->edn pr-str)))
      {:errors {:phase   :resolving
                :message (-> element
                             (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:fouttekst"])
                             (.getFirstChild)
                             (.getTextContent))}})))

(defn- handle-rio-relation-getter-response [^Element element]
  {:post [(s/assert ::Relation/relation-vector %)]}
  (or
    (when (goedgekeurd? element)
      (when-let [samenhang (-> element xml-utils/element->edn
                               :opvragen_opleidingsrelatiesBijOpleidingseenheid_response
                               :samenhangOpleidingseenheid)]
        (when-let [related-eduspecs (-> samenhang :gerelateerdeOpleidingseenheid)]
          (mapv (fn [m]
                  {:valid-from                   (:opleidingsrelatieBegindatum m)
                   :valid-to                     (:opleidingsrelatieEinddatum m)
                   :parent-opleidingseenheidcode (:opleidingseenheidcode samenhang)
                   :child-opleidingseenheidcode  (:opleidingseenheidcode m)})
                (if (map? related-eduspecs) [related-eduspecs] related-eduspecs)))))
    []))

(defn- handle-rio-getter-response [^Element element]
  (when (goedgekeurd? element)
    (-> element xml-utils/element->edn json/write-str)))

(defn make-datamap
  [sender-oin recipient-oin]
  {:schema   schema
   :contract contract
   :validator validator
   :to-url    (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn- extract-getter-response
  [{:keys [success body status]} type]
  (when-not success
    (throw (ex-info (str "Invalid http status " status)
                    {:body body, :status status})))

  (when-not (re-find (re-pattern (str "ns2:opvragen_" type "_response")) body)
    (throw (ex-info "Invalid response"
                    {:body body})))
  body)

(defn- handle-opvragen-request [type request]
  (let [response-handler
        (case type "rioIdentificatiecode" handle-rio-resolver-response
                   "opleidingsrelatiesBijOpleidingseenheid" handle-rio-relation-getter-response
                   handle-rio-getter-response)]
    (-> request
        http-utils/send-http-request
        (extract-getter-response type)
        xml-utils/xml->dom
        .getDocumentElement
        (xml-utils/get-in-dom ["SOAP-ENV:Body" (str "ns2:opvragen_" type "_response")])
        response-handler)))

;; TODO: resolver should just return the opleidingscode when there are no errors.
(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an `education-specification-id` and an
  `institution-oin` and returns a map with the corresponding RIO
  opleidingscode, or errors."
  [{:keys [root-url credentials recipient-oin]}]
  (fn resolver
    [education-specification-id institution-oin]
    {:pre [institution-oin]}
    (let [datamap (make-datamap institution-oin recipient-oin)
          type    "rioIdentificatiecode"
          action  (str "opvragen_" type)]
      (when (some? education-specification-id)
        (let [xml (soap/prepare-soap-call action
                                          [[:duo:eigenOpleidingseenheidSleutel education-specification-id]]
                                          datamap
                                          credentials)]
          (assert (not (errors? xml)) (format "Errors in soap/prepare-soap-call for action %s and eduspec-id %s; %s" action education-specification-id (pr-str xml)))
          (handle-opvragen-request type
                                   (assoc credentials
                                     :url          (str root-url "raadplegen4.0")
                                     :method       :post
                                     :body         xml
                                     :headers      {"SOAPAction" (str contract "/opvragen_" type)}
                                     :content-type :xml)))))))

(def TODO-onderwijsaanbiedercode "110A133") ; TODO replace by id

(defn- valid-onderwijsbestuurcode? [code]
  (re-matches #"\d\d\dB\d\d\d" code))

(defn make-getter
  "Return a function that looks up an 'aangeboden opleiding' by id.

  The getter takes an program or course id and returns a map of
  data with the RIO attributes, or errors."
  [{:keys [root-url credentials recipient-oin]}]
  (fn getter [institution-oin type id & [pagina]]
    {:pre [(some? id)]}
    (when-not (valid-get-actions type)
      (throw (ex-info "Invalid get action" {:action type})))

    (if (and (= type "opleidingseenhedenVanOrganisatie")
             (not (valid-onderwijsbestuurcode? id)))
      (let [error-msg (format "onderwijsbestuurcode %s has invalid format" id)]
        (log/debug error-msg)
        {:errors {:phase   :fetching-rio
                  :message error-msg}})

      (let [rio-sexp (case type
                       "opleidingseenhedenVanOrganisatie"
                       [[:duo:onderwijsbestuurcode id]
                        [:duo:pagina (or pagina 0)]]

                       "aangebodenOpleidingenVanOrganisatie"
                       [[:duo:onderwijsaanbiedercode TODO-onderwijsaanbiedercode]
                        [:duo:pagina (or pagina 0)]]

                       "opleidingsrelatiesBijOpleidingseenheid"
                       [[:duo:opleidingseenheidcode id]]

                       "aangebodenOpleiding"
                       [[:duo:aangebodenOpleidingCode id]])
            xml (soap/prepare-soap-call (str "opvragen_" type) rio-sexp (make-datamap institution-oin recipient-oin) credentials)]
        (assert (not (errors? xml)) "unexpected error in request body")
        (handle-opvragen-request type
                                 (assoc credentials
                                   :url          (str root-url "raadplegen4.0")
                                   :method       :post
                                   :body         xml
                                   :headers      {"SOAPAction" (str contract "/opvragen_" type)}
                                   :content-type :xml))))))
