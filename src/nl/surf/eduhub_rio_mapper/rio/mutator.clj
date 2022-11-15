(ns nl.surf.eduhub-rio-mapper.rio.mutator
  (:require [clojure.data.xml :as clj-xml]
            [clojure.spec.alpha :as s]
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
    (throw (ex-info "Rejected by RIO"
                    {:message (-> element
                                  (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:fouttekst"])
                                  (.getFirstChild)
                                  (.getTextContent))
                     :error-code (-> element
                                     (xml-utils/get-in-dom ["ns2:foutmelding" "ns2:foutcode"])
                                     (.getFirstChild)
                                     (.getTextContent))})))
  (-> element
      xml-utils/dom->str
      clj-xml/parse-str
      xml-utils/xml-event-tree->edn))

;; Returns context needed for executing a mutation.
(defn make-mutator-context
  [{:keys [recipient-oin credentials] :as context} request-poster]
  {:pre [(some? (:certificate credentials)) recipient-oin]}

  (assoc context :request-poster request-poster))

(defn mutate! [{:keys [action sender-oin rio-sexp] :as mutation} {:keys [recipient-oin credentials root-url request-poster]}]
  {:pre [action recipient-oin sender-oin rio-sexp
         (s/valid? ::Mutation/mutation-response mutation)
         (vector? (first rio-sexp))
         sender-oin]}
  (-> {:url          (str root-url "beheren4.0")
       :method       :post
       :body         (soap/prepare-soap-call action
                                             rio-sexp
                                             (make-datamap sender-oin recipient-oin)
                                             credentials
                                             sender-oin
                                             recipient-oin)
       :headers      {"SOAPAction" (str contract "/" action)}
       :content-type :xml}
      (merge credentials)
      (request-poster)
      (get :body)
      (xml-utils/str->dom)
      (.getDocumentElement)
      (xml-utils/get-in-dom ["SOAP-ENV:Body" (str "ns2:" action "_response")])
      (guard-rio-mutate-response (str action))))
