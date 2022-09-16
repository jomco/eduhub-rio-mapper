(ns nl.surf.eduhub-rio-mapper.xml-utils
  (:require [clj-http.client :as http]
            [clojure.data.xml :as clj-xml]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  [:import [java.io StringWriter StringReader ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest Signature KeyStore KeyStore$PrivateKeyEntry KeyStore$PasswordProtection]
           [java.util Base64]
           [javax.xml.crypto.dsig CanonicalizationMethod]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform OutputKeys Transformer TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           (javax.xml.xpath XPathConstants XPathFactory)
           [org.apache.xml.security Init]
           [org.apache.xml.security.c14n Canonicalizer]
           [org.w3c.dom Document Element NodeList]
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

(defn- clean-document! [document]
  (let [factory (XPathFactory/newInstance)
        xpath (.newXPath factory)
        ^NodeList nodelist (.evaluate xpath "//text()[normalize-space()='']", document, XPathConstants/NODESET)]
    (doseq [i (range (.getLength nodelist))]
      (let [node (.item nodelist i)
            parent (.getParentNode node)]
        (.removeChild parent node)))))

(defn make-transformer []
  (let [factory (TransformerFactory/newInstance)]
    (doto (.newTransformer factory)
      (.setOutputProperty OutputKeys/ENCODING, "UTF-8")
      (.setOutputProperty OutputKeys/OMIT_XML_DECLARATION, "yes")
      (.setOutputProperty OutputKeys/INDENT, "yes"))))

(defn xml->dom
  "Parses string with XML content into org.w3c.dom.Document."
  ^Document [^String xml]
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
  ([dom]
   (dom->xml dom (-> (TransformerFactory/newInstance) .newTransformer)))
  ([dom ^Transformer transformer]
   (do-string-writer
     #(.transform transformer (DOMSource. dom) (StreamResult. ^StringWriter %)))))

(defn sexp->dom
  "Converts XML document of data.xml representation in s-expression format into org.w3c.dom.Document."
  [sexp]
  (-> sexp sexp->xml xml->dom))

(defn- dom-reducer-jvm [^Element element tagname] (.item (.getElementsByTagName element tagname) 0))

(defn get-in-dom
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  ^Element [current-element tag-names]
  (reduce dom-reducer-jvm current-element tag-names))

(defn canonicalize-excl
  "Returns a canonical string representation of the supplied Element."
  [^Element element inclusive-ns]
  (Init/init)
  (do-byte-array-outputstream
    #(.canonicalizeSubtree (Canonicalizer/getInstance CanonicalizationMethod/EXCLUSIVE) element inclusive-ns false %)))

(defn- keystore-with-resource [^KeyStore jks resource ^String keystore-password]
  {:pre [(some? resource)]}
  (with-open [in (io/input-stream resource)]
    (.load jks in (.toCharArray keystore-password)))
  jks)

(defn keystore [^String keystore-resource-name ^String keystore-password]
  (keystore-with-resource (KeyStore/getInstance "JKS") (io/resource keystore-resource-name) keystore-password))

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

;; TODO: remove
(def dev-credentials (delay (credentials "keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")))

;; TODO: move to test code / fixtures
(def test-credentials (delay (credentials "test/keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")))

(defn format-xml [xml]
  {:pre [(string? xml)]}
  (let [document (xml->dom xml)]
    (clean-document! document)
    (dom->xml document (make-transformer))))

(defn post
  [url body soap-action {:keys [keystore keystore-pass truststore truststore-pass]}]
  (http/post url
             {:headers          {"SOAPAction" soap-action}
              :body             body
              :content-type     "text/xml; charset=utf-8"
              :throw-exceptions false
              :keystore         keystore
              :keystore-type    "jks"
              :keystore-pass    keystore-pass
              :trust-store      truststore
              :trust-store-type "jks"
              :trust-store-pass truststore-pass}))

(defn post-body
  [url body rio-datamap action credentials]
  (let [{:keys [body status]} (post url body (str (:contract rio-datamap) "/" action) credentials)]
    (log/debug (format "POST %s %s %s" url action status))
    body))

(defn- dom-reducer [element tagname] (first (filter #(= tagname (:tag %)) (:content element))))

(defn get-in-xml
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  [current-element tag-names]
  (reduce dom-reducer current-element (map keyword tag-names)))

(defn- different-keys? [content]
  (when content
    (let [unique-tags (set (keep :tag content))]
      (= (count unique-tags) (count content)))))

(defn xml->edn
  "Recursively convert xml element as produced by clojure.data.xml/parse-str to native data structure.
   It uses a map with keywords :content, :tag and :attrs for individual nodes."
  [element]
  (cond
    (nil? element) nil
    (string? element) element
    (sequential? element) (if (> (count element) 1)
                            (if (different-keys? element)
                              (reduce into {} (map (partial xml->edn ) element))
                              (map xml->edn element))
                            (xml->edn  (first element)))
    (and (map? element) (empty? element)) {}
    (map? element) (if (:attrs element)
                     {(:tag element) (xml->edn (:content element))
                      (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
                     {(:tag element) (xml->edn  (:content element))})
    :else nil))
