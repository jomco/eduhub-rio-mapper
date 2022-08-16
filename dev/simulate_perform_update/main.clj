(ns simulate-perform-update.main
  (:require
    [clojure.data.json :as json]
    [clojure.data.xml :as clj-xml]
    [nl.surf.eduhub-rio-mapper.ooapi.endpoints :as endpoints]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.soap :as soap]))

(defn- single-xml-unwrapper [element tag]
  (-> element
      (xml-utils/get-in-dom [tag])
      (.getFirstChild)
      (.getTextContent)))

(defn handle-rio-response [element]
  (let [goedgekeurd (single-xml-unwrapper element "ns2:requestGoedgekeurd")]
    (if (= "true" goedgekeurd)
      {:code (single-xml-unwrapper element "ns2:opleidingseenheidcode")}
      {:errors (.getTextContent (.getFirstChild (xml-utils/get-in-dom element ["ns2:foutmelding" "ns2:fouttekst"])))})))

(defn make-rio-bridge [credentials]
  (fn [ooapi-id]
    (if (nil? ooapi-id)
      nil
      (let [action "opvragen_rioIdentificatiecode"
            xml (soap/prepare-soap-call action
                                        [[:duo:eigenOpleidingseenheidSleutel ooapi-id]]
                                        soap/raadplegen
                                        credentials)]
        (-> (xml-utils/post-body (:dev-url soap/raadplegen) xml soap/raadplegen action credentials)
            (xml-utils/xml->dom)
            (.getDocumentElement)
            (xml-utils/get-in-dom,, ["SOAP-ENV:Body" "ns2:opvragen_rioIdentificatiecode_response"])
            (handle-rio-response))))))

(defn- dom-reducer [element tagname] (first (filter #(= tagname (:tag %)) (:content element))))

(defn get-in-xml
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  [current-element tag-names]
  (reduce dom-reducer current-element (map keyword tag-names)))

(defn parse-response [xml action]
  (let [root (clj-xml/parse-str xml)
        response-element (get-in-xml root ["Body" (str action "_response")])
        goedgekeurd (= "true" (-> (get-in-xml response-element ["requestGoedgekeurd"]) :content first))
        errors (if goedgekeurd nil (-> (get-in-xml response-element ["foutmelding" "fouttekst"]) :content first))
        code (-> (get-in-xml response-element ["opleidingseenheidcode"]) :content first)]
    {:goedgekeurd goedgekeurd :code code :errors errors :response (xml-utils/format-xml (clj-xml/emit-str response-element))}))

(defn -main [ooapi-mode rio-mode type id]
  (let [live-run (#{"execute" "execute-verbose"} rio-mode)
        credentials (xml-utils/credentials "keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")
        bridge (case ooapi-mode "file" endpoints/ooapi-file-bridge
                                "local" (endpoints/ooapi-http-bridge-maker "http://localhost:8080/")
                                "demo04" (endpoints/ooapi-http-bridge-maker "http://demo04.test.surfeduhub.nl/")
                                "demo05" (endpoints/ooapi-http-bridge-maker "http://demo05.test.surfeduhub.nl/")
                                "demo06" (endpoints/ooapi-http-bridge-maker "http://demo06.test.surfeduhub.nl/")
                                "dev" (endpoints/ooapi-http-bridge-maker endpoints/ooapi-root-url))
        updater (case type "education-specification" endpoints/education-specification-updated
                           "program" endpoints/program-updated
                           "course" endpoints/course-updated)
        rio-bridge (if live-run (make-rio-bridge credentials) (fn [_] "1009O1234"))
        {:keys [action rio-sexp-fn errors ooapi]} (updater id bridge rio-bridge)]
    (if (some? errors)
      (prn errors)
      (let [rio-sexp (rio-sexp-fn ooapi)
            xml (soap/prepare-soap-call action [rio-sexp] soap/beheren credentials)
            executor (fn [] (-> (xml-utils/post-body (:dev-url soap/beheren) xml soap/beheren action credentials)
                                (parse-response ,, action)))]
        (case rio-mode
          "dry-run" (println (xml-utils/format-xml xml))
          "show-ooapi" (print (json/write-str ooapi))
          "execute" (prn (select-keys (executor) [:goedgekeurd :code]))
          "execute-verbose" (do (println "XML REQUEST" xml ["Body"])
                                (let [{:keys [response errors] :as resp} (executor)]
                                  (if (some? errors) (println "ERRORS" errors)
                                                     (println "RESPONSE" response))
                                  (prn (select-keys resp [:goedgekeurd :code])))))))))
