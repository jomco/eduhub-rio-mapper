(ns nl.surf.eduhub-rio-mapper.xml-utils
  (:require [clj-http.client :as http]
            [clojure.data.xml :as clj-xml]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  [:import [java.io StringWriter StringReader ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest Signature KeyStore KeyStore$PrivateKeyEntry KeyStore$PasswordProtection]
           [java.util Base64]
           [javax.xml.crypto.dsig CanonicalizationMethod]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [org.apache.xml.security Init]
           [org.apache.xml.security.c14n Canonicalizer]
           [org.w3c.dom Element]
           [org.xml.sax InputSource]])

(defn digest-sha256
  "Returns sha-256 digest in base64 format."
  [^String inputstring]
  (let [input-bytes (.getBytes inputstring StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") input-bytes)]
    (.encodeToString (Base64/getEncoder) digest)))

(defn sign-sha256rsa
  "Returns signature of string input with supplied PrivateKey."
  [^String input pkey]
  (let [signature (Signature/getInstance "SHA256withRSA")
        ^bytes input-bytes (.getBytes input StandardCharsets/UTF_8)]
    (.initSign signature pkey)
    (.update signature input-bytes)
    (.encodeToString (Base64/getEncoder) (.sign signature))))

(defn sexp->xml
  "Returns string with XML document of data.xml representation in s-expression format."
  [sexp]
  (clj-xml/emit-str (clj-xml/sexp-as-element sexp)))

(defn- db-factory ^DocumentBuilderFactory []
  (let [factory (DocumentBuilderFactory/newInstance)]
    (.setNamespaceAware factory true)
    factory))

(defn xml->dom
  "Parses string with XML content into org.w3c.dom.Document."
  [^String xml]
  (let [builder (.newDocumentBuilder (db-factory))
        doc (.parse builder (InputSource. (StringReader. xml)))]
    (.normalize (.getDocumentElement doc))
    doc))

(defn- do-string-writer [f]
  (let [sw (StringWriter.)]
    (f sw)
    (.toString sw)))

(defn- do-byte-array-outputstream [f]
  (let [baos (ByteArrayOutputStream.)]
    (f baos)
    (.toString baos StandardCharsets/UTF_8)))

(defn dom->xml
  "Renders org.w3c.dom.Document to a String."
  [dom]
  (do-string-writer
    #(-> (TransformerFactory/newInstance)
         .newTransformer
         (.transform (DOMSource. dom) (StreamResult. ^StringWriter %)))))

(defn sexp->dom
  "Converts XML document of data.xml representation in s-expression format into org.w3c.dom.Document."
  [sexp]
  (-> sexp sexp->xml xml->dom))

(defn- dom-reducer [^Element element tagname] (.item (.getElementsByTagName element tagname) 0))

(defn get-in-dom
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  [current-element tag-names]
  (reduce dom-reducer current-element tag-names))

(defn canonicalize-excl
  "Returns a canonical string representation of the supplied Element."
  [^Element element inclusive-ns]
  (Init/init)
  (do-byte-array-outputstream
    #(.canonicalizeSubtree (Canonicalizer/getInstance CanonicalizationMethod/EXCLUSIVE) element inclusive-ns false %)))

(defn keystore [^String keystore-resource-name ^String keystore-password]
  (let [jks (KeyStore/getInstance "JKS")]
    (with-open [in (io/input-stream (io/resource keystore-resource-name))]
      (.load jks in (.toCharArray keystore-password)))
    jks))

(defn credentials [^String keystore-resource-name ^String keystore-password ^String keystore-alias
                   ^String truststore-resource-name ^String truststore-password]
  (let [jks ^KeyStore (keystore keystore-resource-name keystore-password)
        truststore ^KeyStore (keystore truststore-resource-name truststore-password)
        char-password ^chars (.toCharArray keystore-password)
        ^KeyStore$PrivateKeyEntry entry (.getEntry jks keystore-alias (KeyStore$PasswordProtection. char-password))
        private-key (.getKey jks keystore-alias char-password)
        certificate (.getEncoded (.getCertificate entry))]
    {:keystore        jks
     :truststore      truststore
     :keystore-pass   keystore-password
     :truststore-pass truststore-password
     :private-key     private-key
     :certificate     certificate}))

(defn format-xml [xml]
  (let [formatted-xml (:out (shell/sh "xmllint" "--pretty" "1" "-" :in xml))]
    (shutdown-agents)
    formatted-xml))

(defn post
  [url body soap-action credentials]
  (http/post url
             {:headers          {"SOAPAction" soap-action}
              :body             body
              :content-type     "text/xml; charset=utf-8"
              :throw-exceptions false
              :keystore         (:keystore credentials)
              :keystore-type    "jks"
              :keystore-pass    (:keystore-pass credentials)
              :trust-store      (:truststore credentials)
              :trust-store-type "jks"
              :trust-store-pass (:truststore-pass credentials)}))

(defn post-body
  [url body soap-action credentials]
  (:body (post url body soap-action credentials)))
