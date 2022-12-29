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

(ns nl.surf.eduhub-rio-mapper.rio.mutator
  (:require [clojure.data.xml :as clj-xml]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
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

(s/def ::Mutation/mutation-response
  (s/and map?
         (s/keys :req-un [::action ::sender-oin ::rio-sexp]
                 :opt-un [::ooapi])))

(defn make-datamap
  [sender-oin recipient-oin]
  (assert [sender-oin recipient-oin])
  {:schema    schema
   :contract  contract
   :validator validator
   :to-url    (str "https://duo.nl/RIO/services/beheren4.0?oin=" recipient-oin)
   :from-url  (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})


(defn- guard-rio-mutate-response [^Element element description]
  {:pre [(some? element)]}
  (loader/log-rio-action-response description element)
  (when-not (loader/goedgekeurd? element)
    (let [code (-> element
                   (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:foutcode"])
                   (.getFirstChild)
                   (.getTextContent))
          msg  (-> element
                  (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:fouttekst"])
                  (.getFirstChild)
                  (.getTextContent))]
      (throw (ex-info (str "Rejected by RIO: " code ": " msg)
                      {:element element}))))
  (-> element
      xml-utils/dom->str
      clj-xml/parse-str
      xml-utils/xml-event-tree->edn))

(defn mutate! [{:keys [action sender-oin rio-sexp] :as mutation}
               {:keys [recipient-oin credentials update-url connection-timeout-millis]}]
  {:pre [action recipient-oin sender-oin rio-sexp update-url
         (s/valid? ::Mutation/mutation-response mutation)
         (vector? (first rio-sexp))
         sender-oin]}
  (-> {:url                update-url
       :method             :post
       :body               (soap/prepare-soap-call action
                                                   rio-sexp
                                                   (make-datamap sender-oin recipient-oin)
                                                   credentials
                                                   sender-oin
                                                   recipient-oin)
       :headers            {"SOAPAction" (str contract "/" action)}
       :connection-timeout connection-timeout-millis
       :content-type       :xml}
      (merge credentials)
      (http-utils/send-http-request)
      (get :body)
      (xml-utils/str->dom)
      (.getDocumentElement)
      (xml-utils/get-in-dom ["SOAP-ENV:Body" (str "ns2:" action "_response")])
      (guard-rio-mutate-response (str action))))
