(ns nl.surf.eduhub-rio-mapper.soap
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.xml-validator :as validator])
  (:import [java.time OffsetDateTime]
           [java.time.format DateTimeFormatterBuilder DateTimeFormatter]
           [java.util Base64 UUID]
           [javax.xml.crypto.dsig CanonicalizationMethod]
           [org.w3c.dom Element NodeList Document]))

;;; Constants

(def x509v3 "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3")
(def base64binary "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary")
(def digest-algorithm "http://www.w3.org/2001/04/xmlenc#sha256")
(def signature-algorithm "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256")
(def ontvangende-instantie "00000001800866472000")
(def verzendende-instantie "0000000700025BE00000")

(def raadplegen {:schema    "http://duo.nl/schema/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4"
                 :contract  "http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4"
                 :to-url    (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" ontvangende-instantie)
                 :dev-url   "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0"
                 :validator (validator/create-validation-fn "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd")})

(def beheren {:schema    "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4"
              :contract  "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4"
              :to-url    (str "https://duo.nl/RIO/services/beheren4.0?oin=" ontvangende-instantie)
              :dev-url   "https://vt-webservice.duo.nl:6977/RIO/services/beheren4.0"
              :validator (validator/create-validation-fn "DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd")})

(s/def ::http-url (re-spec #"http(s)?://.*"))
(s/def ::schema ::http-url)
(s/def ::contract ::http-url)
(s/def ::to-url ::http-url)
(s/def ::dev-url ::http-url)
(s/def ::rio-datamap (s/keys :req-un [::schema ::contract ::to-url ::dev-url]))

(def from-url (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" verzendende-instantie))
(def wsu-schema "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd")
(def ws-addressing "http://www.w3.org/2005/08/addressing")
(def soap-envelope "http://schemas.xmlsoap.org/soap/envelope/")

(def expiry-in-minutes 10)

(def ^:private millisecond-precision 3)
(def ^:private ^DateTimeFormatter instant-formatter (.toFormatter (.appendInstant (DateTimeFormatterBuilder.) millisecond-precision)))
(defn format-instant [instant] (.format instant-formatter instant))

(defn- generate-timestamp ^OffsetDateTime [] (OffsetDateTime/now))
(defn- generate-message-id [] (UUID/randomUUID))
(defn- base64encode [bytes] (.encodeToString (Base64/getEncoder) bytes))
(defn- text-content= [^Element element ^String content] (.setTextContent element content))

(defn- to-ns [prefix schema] (str "xmlns:" prefix "=\"" schema "\""))
(defn xmlns [prefix-schema] (string/join " " (map #(to-ns (first %) (last %)) prefix-schema)))

(def parts-data {"From" ["duo soapenv" ["soapenv:Header" "wsa:From"]]
                 "To"        ["duo soapenv" ["soapenv:Header" "wsa:To"]]
                 "Action"    ["duo soapenv" ["soapenv:Header" "wsa:Action"]]
                 "MessageID" ["duo soapenv" ["soapenv:Header" "wsa:MessageID"]]
                 "Body"      ["duo" ["soapenv:Body"]]
                 "Timestamp" ["wsse wsa duo soapenv" ["soapenv:Header" "wsu:Timestamp"]]})

(defn- wrap-in-envelope [sexp-body contract schema action from to certificate parts]
  {:pre [(some? certificate)]}
  (let [timestamp (generate-timestamp)
        bst-id (UUID/randomUUID)
        message-id (generate-message-id)]
    [:soapenv:Envelope {:xmlns:duo schema
                        :xmlns:soapenv "http://schemas.xmlsoap.org/soap/envelope/"
                        :xmlns:wsu wsu-schema}
     [:soapenv:Header {:xmlns:wsa "http://www.w3.org/2005/08/addressing"}
      [:wsse:Security {:xmlns:wsse "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"}
       [:wsse:BinarySecurityToken {:EncodingType base64binary :ValueType x509v3 :wsu:Id (str "X509-" bst-id)}
        (base64encode certificate)]
       [:ds:Signature {:xmlns:ds "http://www.w3.org/2000/09/xmldsig#"}
        [:ds:SignedInfo {:xmlns:ec CanonicalizationMethod/EXCLUSIVE}
         [:ds:CanonicalizationMethod {:Algorithm CanonicalizationMethod/EXCLUSIVE}
          [:ec:InclusiveNamespaces {:PrefixList "wsa duo soapenv"}]]
         [:ds:SignatureMethod {:Algorithm signature-algorithm}]
         (for [[nodename [prefixlist _path]] parts]
           [:ds:Reference {:URI (str "#id-" nodename)}
            [:ds:Transforms
             [:ds:Transform {:Algorithm CanonicalizationMethod/EXCLUSIVE}
              [:ec:InclusiveNamespaces {:PrefixList prefixlist}]]]
            [:ds:DigestMethod {:Algorithm digest-algorithm}]
            [:ds:DigestValue]])]
        [:ds:SignatureValue]
        [:ds:KeyInfo
         [:wsse:SecurityTokenReference
          [:wsse:Reference {:URI (str "#X509-" bst-id) :ValueType x509v3}]]]]
       [:wsu:Timestamp {:wsu:Id "id-Timestamp"}
        [:wsu:Created (format-instant timestamp)]
        [:wsu:Expires (format-instant (.plusMinutes timestamp expiry-in-minutes))]]]
      [:wsa:Action {:wsu:Id "id-Action"} (str contract "/" action)]
      [:wsa:From {:wsu:Id "id-From"}
       [:wsa:Address from]]
      [:wsa:MessageID {:wsu:Id "id-MessageID"} message-id]
      [:wsa:To {:wsu:Id "id-To"} to]]
     [:soapenv:Body {:wsu:Id "id-Body"} sexp-body]]))

;; uri looks like: #id-Name
;; Remove first 4 characters to get the name.
(defn- id-reference-to-part-name [^String uri] (.substring uri 4))

(defn- add-digest-to-references [envelope-node ^Element signed-info-node]
  (let [^NodeList elements (.getElementsByTagName signed-info-node "ds:Reference")]
    (doseq [i (range (.getLength elements))]
      (let [^Element reference (.item elements i)
            [prefixlist path] (parts-data (id-reference-to-part-name (.getAttribute reference "URI")))]
        (text-content= (xml-utils/get-in-dom reference ["ds:DigestValue"])
                       (xml-utils/digest-sha256
                         (xml-utils/canonicalize-excl (xml-utils/get-in-dom envelope-node path)
                                                      prefixlist)))))))

(defn- calculate-signature [signed-info private-key]
  (xml-utils/sign-sha256rsa (xml-utils/canonicalize-excl signed-info "wsa duo soapenv") private-key))

(defn request-body [action rio-datamap]
  [(keyword (str "duo:" action "_request")) {:xmlns:duo (:schema rio-datamap)}
   [:duo:identificatiecodeBedrijfsdocument (UUID/randomUUID)]
   [:duo:verzendendeInstantie verzendende-instantie]
   [:duo:ontvangendeInstantie ontvangende-instantie]
   [:duo:datumTijdBedrijfsdocument (format-instant (generate-timestamp))]])

(defn convert-to-signed-dom-document
  "Takes a XML document representing a RIO-request, and an action, and wraps it in a signed SOAP org.w3c.dom.Document."
  [sexp-body {:keys [contract schema to-url]} action {:keys [private-key certificate]}]
  (let [from from-url
        ^Document document (xml-utils/sexp->dom (wrap-in-envelope sexp-body contract schema action from to-url certificate parts-data))
        ^Element envelope-node (.getDocumentElement document)
        signature-node (xml-utils/get-in-dom envelope-node ["soapenv:Header" "wsse:Security" "ds:Signature"])
        signed-info-node (xml-utils/get-in-dom signature-node ["ds:SignedInfo"])
        signature-value-node (xml-utils/get-in-dom signature-node ["ds:SignatureValue"])]
    (add-digest-to-references envelope-node signed-info-node)
    (text-content= signature-value-node (calculate-signature signed-info-node private-key))
    document))

(defn check-valid-xsd [sexp rio-datamap]
  ; TODO currently only prints warning to STDOUT
  ((:validator rio-datamap) (xml-utils/sexp->xml sexp))
  sexp)

(defn prepare-soap-call
  "Converts `rio-sexp` to a signed soap document. See GLOSSARY.md for information about arguments."
  [action rio-sexp rio-datamap credentials]
  (-> (request-body action rio-datamap)
      (into rio-sexp)
      (check-valid-xsd rio-datamap)
      (convert-to-signed-dom-document rio-datamap action credentials)
      (xml-utils/dom->xml)))

(defn send-soap-call [xml action rio-datamap credentials]
  (let [soap-action (str (:contract rio-datamap) "/" action)]
    (xml-utils/post-body (:dev-url rio-datamap) xml soap-action credentials)))

(defn make-soap-call [action rio-sexp rio-datamap credentials request-xml-handler response-xml-handler]
  (let [xml (prepare-soap-call action rio-sexp rio-datamap credentials)
        response (send-soap-call xml action rio-datamap credentials)]
    (request-xml-handler xml)
    (response-xml-handler (xml-utils/format-xml response))))
