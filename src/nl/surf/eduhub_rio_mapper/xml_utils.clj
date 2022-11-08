(ns nl.surf.eduhub-rio-mapper.xml-utils
  (:require [clojure.data.xml :as clj-xml])
  [:import [java.io StringWriter StringReader]
           [javax.xml.parsers DocumentBuilderFactory]
           [javax.xml.transform Transformer TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [org.w3c.dom Document Element]
           [org.xml.sax InputSource]])

(defn- do-string-writer [f]
  (let [sw (StringWriter.)]
    (f sw)
    (.toString sw)))

(defn- db-factory ^DocumentBuilderFactory []
  (let [factory (DocumentBuilderFactory/newInstance)]
    (.setNamespaceAware factory true)
    factory))

(defn- different-keys? [content]
  (when content
    (let [unique-tags (set (keep :tag content))]
      (= (count unique-tags) (count content)))))

(defn xml-event-tree->edn
  "Convert xml event tree (as produced by clojure.data.xml/parse-str) on simplified edn structure.
   It uses a map with keywords :content, :tag and :attrs for individual nodes."
  [element]
  (cond
    (nil? element) nil
    (string? element) element
    (sequential? element) (if (> (count element) 1)
                            (if (different-keys? element)
                              (reduce into {} (map (partial xml-event-tree->edn ) element))
                              (map xml-event-tree->edn element))
                            (xml-event-tree->edn  (first element)))
    (and (map? element) (empty? element)) {}
    (map? element) (if (:attrs element)
                     {(:tag element) (xml-event-tree->edn (:content element))
                      (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
                     {(:tag element) (xml-event-tree->edn  (:content element))})
    :else nil))

;;; Conversion functions between the following formats:
;;; sexp: hiccup-like s-expressions
;;; xml: string with xml document
;;; dom: Java Document with parsed xml
;;; element: Single Element within a DOM tree
;;; edn: Clojure representation of XML document

(defn xml->dom
  "Parses string with XML content into org.w3c.dom.Document."
  ^Document [^String xml]
  (let [builder (.newDocumentBuilder (db-factory))
        doc (.parse builder (InputSource. (StringReader. xml)))]
    (.normalize (.getDocumentElement doc))
    doc))

(defn dom->xml
  "Renders org.w3c.dom.Document to a String."
  ([dom]
   (dom->xml dom (-> (TransformerFactory/newInstance) .newTransformer)))
  ([dom ^Transformer transformer]
   (do-string-writer
     #(.transform transformer (DOMSource. dom) (StreamResult. ^StringWriter %)))))

(defn element->edn
  "Convert org.w3c.dom.Element into simplified edn structure."
  [^Element element]
  (-> element
      dom->xml
      clj-xml/parse-str
      xml-event-tree->edn))

(defn- dom-reducer-jvm [^Element element tagname]
  {:post [(some? %)]}
  (when element
    (.item (.getElementsByTagName element tagname) 0)))

(defn get-in-dom
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  ^Element [current-element tag-names]
  {:post [(some? %)]}
  (reduce dom-reducer-jvm current-element tag-names))
