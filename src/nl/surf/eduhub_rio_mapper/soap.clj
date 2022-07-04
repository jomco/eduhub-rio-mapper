(ns nl.surf.eduhub-rio-mapper.soap
  (:import (nl.surf.eduhub_rio_mapper SoapSigner)
           (java.util ArrayList)
           (java.time.format DateTimeFormatter)
           (java.time OffsetDateTime)))

(defn soap-attr-id [id]
  {:Id (str "id-" id)})

(defn apply-soap [docroot action]
  [:soapenv:Envelope {:xmlns:soapenv "http://schemas.xmlsoap.org/soap/envelope/"}
   [:soapenv:Header {:xmlns:wsa "http://www.w3.org/2005/08/addressing"}
    [:wsse:Security {:xmlns:wsse "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                     :xmlns:wsu "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"}
     [:wsse:BinarySecurityToken {:EncodingType "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
                                 :ValueType    "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3"}]
     [:wsu:Timestamp (soap-attr-id "timestamp")
      [:wsu:Created (.format DateTimeFormatter/ISO_INSTANT (OffsetDateTime/now ))]
      [:wsu:Expires (.format DateTimeFormatter/ISO_INSTANT (.plusMinutes (OffsetDateTime/now) 10))]]]
    [:wsa:Action (soap-attr-id "action") action]
    [:wsa:From (soap-attr-id "from")
     [:wsa:Address "http://www.w3.org/2005/08/addressing/anonymous?oin=0000000700099ZZ00000"]]
    [:wsa:MessageID (soap-attr-id "message-id") "uuid:38aefb9e-f6f5-4b77-82f4-30db9e22c98a"]
    [:wsa:To (soap-attr-id "to") "http://localhost/RIO/services/beheren4.0?oin=00000001800866472000"]]
   [:soapenv:Body (soap-attr-id "body") docroot]])

(defn signed-soap-str [soap-str]
  (let [reflist (ArrayList.)
        refs ["#id-action" "#id-from" "#id-to" "#id-message-id" "#id-body" "#id-timestamp"]]
    (doseq [n refs] (.add reflist n))
    (.signedXml (SoapSigner. "keystore.jks" "test-surf" "xxxxxx", soap-str reflist))))
