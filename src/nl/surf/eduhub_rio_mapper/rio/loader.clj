;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.rio.loader
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require
    [clojure.data.json :as json]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.Relation :as-alias Relation]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(def valid-get-actions #{"opleidingseenhedenVanOrganisatie" "aangebodenOpleidingenVanOrganisatie"
                         "opleidingsrelatiesBijOpleidingseenheid" "aangebodenOpleiding"})

(def schema "http://duo.nl/schema/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def contract "http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def validator  (xml-validator/create-validation-fn "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd"))

(defn- single-xml-unwrapper
  "Find the content of the first child of `element` with type `tag`.

  Returns `nil` if no matching element is there"
  [element tag]
  (some-> element
          (xml-utils/get-in-dom [tag])
          (.getFirstChild)
          (.getTextContent)))

(defn goedgekeurd? [^Element element]
  {:pre [element]}
  (= "true" (single-xml-unwrapper element "ns2:requestGoedgekeurd")))

(defn log-rio-action-response [msg element]
  (log/debugf (format "RIO %s; SUCCESS: %s" msg (goedgekeurd? element))))

(defn- rio-resolver-response [^Element element]
  {:pre [element]}
  (if (goedgekeurd? element)
    ;; TODO: this is ugly, but we don't know at this stage what entity we tried to resolve.
    (let [code (or (single-xml-unwrapper element "ns2:opleidingseenheidcode")
                   (single-xml-unwrapper element "ns2:aangebodenOpleidingCode"))]
      (log-rio-action-response (str "SUCCESSFUL RESOLVE:" code) element)
      code)
    (let [id (-> element xml-utils/element->edn :opvragen_rioIdentificatiecode_response :foutmelding :sleutelgegeven :sleutelwaarde)]
      (log-rio-action-response (str "RESOLVE FAILED - ID: " id) element)
      nil)))

(defn- rio-relation-getter-response [^Element element]
  {:post [(s/valid? (s/nilable ::Relation/relation-vector) %)]}
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
              (if (map? related-eduspecs) [related-eduspecs] related-eduspecs))))))

(defn- rio-xml-getter-response [^Element element]
  (assert (goedgekeurd? element))                           ; should fail elsewhere with error http code otherwise
  (-> element xml-utils/dom->str))

(defn- rio-json-getter-response [^Element element]
  (assert (goedgekeurd? element))                           ; should fail elsewhere with error http code otherwise
  (-> element xml-utils/element->edn json/write-str))

(defn make-datamap
  [sender-oin recipient-oin]
  {:schema   schema
   :contract contract
   :validator validator
   :to-url    (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn- extract-getter-response
  [{:keys [body]} type]
  (when-not (re-find (re-pattern (str "ns2:opvragen_" type "_response")) body)
    (throw (ex-info "Invalid response" {:body body})))
  body)

(defn- handle-opvragen-request [type response-handler request]
  (-> request
      http-utils/send-http-request
      (extract-getter-response type)
      xml-utils/str->dom
      .getDocumentElement
      (xml-utils/get-in-dom ["SOAP-ENV:Body" (str "ns2:opvragen_" type "_response")])
      response-handler))

(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an `id` and an `institution-oin` and returns a
  map with errors, or the corresponding RIO opleidingscode."
  [{:keys [read-url credentials recipient-oin]}]
  {:pre [read-url]}
  (fn resolver
    [type id institution-oin]
    {:pre [institution-oin]}

    (let [datamap (make-datamap institution-oin recipient-oin)
          action  (str "opvragen_rioIdentificatiecode")]
      (when id
        (let [xml (soap/prepare-soap-call action
                                          [[(case type
                                                  "education-specification" :duo:eigenOpleidingseenheidSleutel
                                                  ("course" "program") :duo:eigenAangebodenOpleidingSleutel)
                                            id]]
                                          datamap
                                          credentials
                                          institution-oin
                                          recipient-oin)]
          (handle-opvragen-request "rioIdentificatiecode"
                                   rio-resolver-response
                                   (assoc credentials
                                     :url          read-url
                                     :method       :post
                                     :body         xml
                                     :headers      {"SOAPAction" (str contract "/opvragen_rioIdentificatiecode")}
                                     :content-type :xml)))))))

(defn- valid-onderwijsbestuurcode? [code]
  (re-matches #"\d\d\dB\d\d\d" code))

(defn- response-handler-for-type [response-type type]
  (case response-type
    :xml rio-xml-getter-response
    :json rio-json-getter-response
    ;; If unspecified, use edn for relations and json for everything else
    (if (= type "opleidingsrelatiesBijOpleidingseenheid")
      rio-relation-getter-response
      rio-json-getter-response)))

(defn make-getter
  "Return a function that looks up an 'aangeboden opleiding' by id.

  The getter takes an program or course id and returns a map of
  data with the RIO attributes, or errors."
  [{:keys [read-url credentials recipient-oin]}]
  {:pre [read-url]}
  (fn getter [{::ooapi/keys [id] :keys [institution-oin pagina response-type] :or {pagina 0} ::rio/keys [type opleidingscode]}]
    (when-not (valid-get-actions type)
      (throw (ex-info "Invalid get action" {:action type})))

    (if (and (= type "opleidingseenhedenVanOrganisatie")
             (not (valid-onderwijsbestuurcode? id)))
      (let [error-msg (format "onderwijsbestuurcode %s has invalid format" id)]
        (log/debug error-msg)
        {:errors {:phase   :fetching-rio
                  :message error-msg}})

      (let [rio-sexp (case type
                       ;; Command line only.
                       "opleidingseenhedenVanOrganisatie"
                       [[:duo:onderwijsbestuurcode id]
                        [:duo:pagina pagina]]

                       ;; Command line only.
                       "aangebodenOpleidingenVanOrganisatie"
                       [[:duo:onderwijsaanbiedercode id]
                        [:duo:pagina pagina]]

                       "opleidingsrelatiesBijOpleidingseenheid"
                       [[:duo:opleidingseenheidcode opleidingscode]]

                       "aangebodenOpleiding"
                       [[:duo:aangebodenOpleidingCode id]])
            xml (soap/prepare-soap-call (str "opvragen_" type) rio-sexp (make-datamap institution-oin recipient-oin) credentials institution-oin recipient-oin)]
        (handle-opvragen-request type
                                 (fn [element]
                                   (log-rio-action-response type element)
                                   ((response-handler-for-type response-type type) element))
                                 (assoc credentials
                                   :url          read-url
                                   :method       :post
                                   :body         xml
                                   :headers      {"SOAPAction" (str contract "/opvragen_" type)}
                                   :content-type :xml))))))
