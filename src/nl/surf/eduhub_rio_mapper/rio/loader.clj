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
    [clojure.data.xml :as clj-xml]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.logging :as logging]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.Relation :as-alias Relation]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element)))

(def aangeboden-opleiding "aangebodenOpleiding")
(def aangeboden-opleidingen-van-organisatie "aangebodenOpleidingenVanOrganisatie")
(def opleidingseenheid "opleidingseenheid")
(def opleidingseenheden-van-organisatie "opleidingseenhedenVanOrganisatie")
(def opleidingsrelaties-bij-opleidingseenheid "opleidingsrelatiesBijOpleidingseenheid")

(def opleidingseenheid-namen
  #{:hoOpleiding :particuliereOpleiding :hoOnderwijseenhedencluster :hoOnderwijseenheid})

(def aangeboden-opleiding-namen
  #{:aangebodenHOOpleidingsonderdeel :aangebodenHOOpleiding :aangebodenParticuliereOpleiding})

;; NOTE: aangeboden opleidingen are referenced by OOAPI UID
(def aangeboden-opleiding-types #{aangeboden-opleiding
                                  aangeboden-opleidingen-van-organisatie})

(def valid-get-types (into aangeboden-opleiding-types
                           #{opleidingseenheid
                             opleidingseenheden-van-organisatie
                             opleidingsrelaties-bij-opleidingseenheid}))

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
  (logging/with-mdc
    {:identificatiecodeBedrijfsdocument (single-xml-unwrapper element "ns2:identificatiecodeBedrijfsdocument")}
    (log/debugf (format "RIO %s; SUCCESS: %s" msg (goedgekeurd? element)))))

;; De externe identificatie komt niet voor in RIO
;; Handled separately because this is an expected outcome, and handling it is part of the normal program flow.
(def missing-entity "A01161")

(defn- rio-resolver-response [^Element element]
  {:pre [element]}
  (if (goedgekeurd? element)
    ;; TODO: this is ugly, but we don't know at this stage what entity we tried to resolve.
    (let [code (or (single-xml-unwrapper element "ns2:opleidingseenheidcode")
                   (single-xml-unwrapper element "ns2:aangebodenOpleidingCode"))]
      (log-rio-action-response (str "SUCCESSFUL RESOLVE:" code) element)
      code)
    (let [foutmelding (-> element xml-utils/element->edn :opvragen_rioIdentificatiecode_response :foutmelding)
          id (-> foutmelding :sleutelgegeven :sleutelwaarde)]
      (when-not (= missing-entity (:foutcode foutmelding))
        (log-rio-action-response (str "Resolve of object " id " failed with error code " (:foutcode foutmelding)) element)
        (throw (ex-info (str "Resolve of object " id " failed with error code " (:foutcode foutmelding)) {:retryable? false})))
      (log-rio-action-response (str "Object with id (" id ") not found in RIO via resolve") element)
      nil)))

(defn- rio-relation-getter-response [^Element element]
  {:post [(s/valid? (s/nilable ::Relation/relation-vector) %)]}
  (when (goedgekeurd? element)
    (when-let [samenhang (-> element xml-utils/element->edn
                             :opvragen_opleidingsrelatiesBijOpleidingseenheid_response
                             :samenhangOpleidingseenheid)]
      (s/assert ::rio/opleidingscode (:opleidingseenheidcode samenhang))
      (when-let [related-eduspecs (-> samenhang :gerelateerdeOpleidingseenheid)]
        (->> (if (map? related-eduspecs) [related-eduspecs] related-eduspecs)
             ;; Accredited HoOpleidingen have a AFGELEID_VAN relation which is not relevant for the edumapper
             ;; and should be ignored.
             (filter (fn [m] (not= (:opleidingsrelatiesoort m) "AFGELEID_VAN")))
             (mapv (fn [m]
                     (s/assert ::rio/opleidingscode (:opleidingseenheidcode m))
                     {:valid-from             (:opleidingsrelatieBegindatum m)
                      :valid-to               (:opleidingsrelatieEinddatum m)
                      :opleidingseenheidcodes #{(:opleidingseenheidcode samenhang) (:opleidingseenheidcode m)}})))))))

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
   :sender-oin sender-oin
   :recipient-oin recipient-oin
   :to-url    (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn guard-getter-response
  [{:keys [body]} type tag]
  (when-not (re-find (re-pattern (str "<" tag "[^>]*>")) body)
    (throw (ex-info (str "Unexpected response, it does not contain tag: " tag)
                    {:type type, :body body})))
  body)

(defn- handle-opvragen-request [type response-handler request]
  (let [tag (str "ns2:opvragen_" type "_response")]
    (-> request
        http-utils/send-http-request
        (guard-getter-response type tag)
        xml-utils/str->dom
        .getDocumentElement
        (xml-utils/get-in-dom ["SOAP-ENV:Body" tag])
        response-handler)))

(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an `id` and an `institution-oin` and returns a
  map with errors, or the corresponding RIO opleidingscode."
  [{:keys [read-url credentials recipient-oin connection-timeout-millis]}]
  {:pre [read-url]}
  (fn resolver
    [type id institution-oin]
    {:pre [institution-oin]}

    (when id
      (logging/with-mdc
        {:soap-action "opvragen_rioIdentificatiecode" :ooapi-id id}
        (let [xml (soap/prepare-soap-call "opvragen_rioIdentificatiecode"
                                          [[(case type
                                              "education-specification" :duo:eigenOpleidingseenheidSleutel
                                              ("course" "program") :duo:eigenAangebodenOpleidingSleutel)
                                            id]]
                                          (make-datamap institution-oin recipient-oin)
                                          credentials)]
          (handle-opvragen-request "rioIdentificatiecode"
                                   rio-resolver-response
                                   (assoc credentials
                                     :url read-url
                                     :method :post
                                     :body xml
                                     :headers {"SOAPAction" (str contract "/opvragen_rioIdentificatiecode")}
                                     :connection-timeout connection-timeout-millis
                                     :content-type :xml)))))))

(defn- valid-onderwijsbestuurcode? [code]
  (re-matches #"\d\d\dB\d\d\d" code))

(defn- response-handler-for-type [response-type type]
  (case response-type
    :literal identity
    :xml     rio-xml-getter-response
    :json    rio-json-getter-response
    ;; If unspecified, use edn for relations and json for everything else
    (if (= type opleidingsrelaties-bij-opleidingseenheid)
      rio-relation-getter-response
      rio-json-getter-response)))

(defn find-opleidingseenheid [rio-code getter institution-oin]
  {:pre [rio-code]}
  (-> (getter {::rio/type           opleidingseenheid
               ::rio/opleidingscode rio-code
               :institution-oin     institution-oin
               :response-type       :xml})
      clj-xml/parse-str
      xml-seq
      (xml-utils/find-in-xmlseq #(when (opleidingseenheid-namen (:tag %)) %))))

(def opvragen-aangeboden-opleiding-soap-action (str "opvragen_" aangeboden-opleiding))
(def opvragen-aangeboden-opleiding-response-tagname (str "ns2:" opvragen-aangeboden-opleiding-soap-action "_response"))

(defn find-aangebodenopleiding
  "Returns aangeboden opleiding as parsed xml document. Returns nil if not found.

  Requires institution-oin and recipient-oin (which should be distinct)."
  [aangeboden-opleiding-code
   institution-oin
   {:keys [read-url credentials recipient-oin] :as _config}]
  {:pre [aangeboden-opleiding-code institution-oin recipient-oin (not= institution-oin recipient-oin)]}
  (let [soap-req (soap/prepare-soap-call opvragen-aangeboden-opleiding-soap-action
                                         [[:duo:aangebodenOpleidingCode aangeboden-opleiding-code]]
                                         (make-datamap institution-oin
                                                                  recipient-oin)
                                         credentials)
        request  (assoc credentials
                   :url read-url
                   :method :post
                   :body soap-req
                   :headers {"SOAPAction" (str contract "/" opvragen-aangeboden-opleiding-soap-action)}
                   :content-type :xml)]
    (-> request
        http-utils/send-http-request
        (guard-getter-response type opvragen-aangeboden-opleiding-response-tagname)
        clj-xml/parse-str
        xml-seq
        (xml-utils/find-in-xmlseq #(and (aangeboden-opleiding-namen (:tag %)) %)))))

(defn rio-finder [getter rio-config {::ooapi/keys [type] ::rio/keys [opleidingscode aangeboden-opleiding-code] :keys [institution-oin] :as _request}]
  (case type
    "education-specification" (find-opleidingseenheid opleidingscode getter institution-oin)
    ("course" "program") (find-aangebodenopleiding aangeboden-opleiding-code institution-oin rio-config)))

(defn make-getter
  "Return a function that looks up an 'aangeboden opleiding' by id.

  The getter takes an program or course id and returns a map of
  data with the RIO attributes, or errors."
  [{:keys [read-url credentials recipient-oin]}]
  {:pre [read-url]}
  (fn getter [{::ooapi/keys [id]
               ::rio/keys   [type opleidingscode]
               :keys        [institution-oin pagina response-type]
               :or          {pagina 0}}]
    {:pre [(or (and (aangeboden-opleiding-types type) id)
               opleidingscode)]}
    (when-not (valid-get-types type)
      (throw (ex-info (str "Unexpected type: " type)
                      {:id             id
                       :opleidingscode opleidingscode
                       :retryable?     false})))

    (when (and (= type opleidingseenheden-van-organisatie)
               (not (valid-onderwijsbestuurcode? opleidingscode)))
      (throw (ex-info (str "Type 'onderwijsbestuurcode' has ID invalid format: " opleidingscode)
                      {:type           type
                       :retryable?     false})))

    (let [soap-action (str "opvragen_" type)
          rio-sexp    (condp = type
                        ;; Command line only.
                        opleidingseenheden-van-organisatie
                        [[:duo:onderwijsbestuurcode opleidingscode] ;; FIXME: this is not an opleidingscode!
                         [:duo:pagina pagina]]

                        ;; Command line only.
                        aangeboden-opleidingen-van-organisatie
                        [[:duo:onderwijsaanbiedercode id]
                         [:duo:pagina pagina]]

                        opleidingsrelaties-bij-opleidingseenheid
                        [[:duo:opleidingseenheidcode opleidingscode]]

                        aangeboden-opleiding
                        [[:duo:aangebodenOpleidingCode id]]

                        opleidingseenheid
                        [[:duo:opleidingseenheidcode opleidingscode]])]
      (logging/with-mdc {:soap-action soap-action}
        (let [xml (soap/prepare-soap-call soap-action
                                          rio-sexp
                                          (make-datamap institution-oin recipient-oin)
                                          credentials)]
          (handle-opvragen-request type
                                   (fn [element]
                                     (log-rio-action-response type element)
                                     ((response-handler-for-type response-type type) element))
                                   (assoc credentials
                                     :url read-url
                                     :method :post
                                     :body xml
                                     :headers {"SOAPAction" (str contract "/" soap-action)}
                                     :content-type :xml)))))))
