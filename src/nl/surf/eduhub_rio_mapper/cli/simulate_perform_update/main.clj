(ns nl.surf.eduhub-rio-mapper.cli.simulate-perform-update.main
  (:require
    [clojure.data.json :as json]
    [clojure.data.xml :as clj-xml]
    [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
    [nl.surf.eduhub-rio-mapper.ooapi.endpoints :as endpoints]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

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
        (if (errors? xml)
          (println xml)
          (-> (xml-utils/post-body (:dev-url soap/raadplegen) xml soap/raadplegen action credentials)
              (xml-utils/xml->dom)
              (.getDocumentElement)
              (xml-utils/get-in-dom,, ["SOAP-ENV:Body" "ns2:opvragen_rioIdentificatiecode_response"])
              (handle-rio-response)))))))

(defn parse-response [xml action]
  (let [root (clj-xml/parse-str xml)
        response-element (xml-utils/get-in-xml root ["Body" (str action "_response")])
        goedgekeurd (= "true" (-> (xml-utils/get-in-xml response-element ["requestGoedgekeurd"]) :content first))
        errors (-> (xml-utils/get-in-xml response-element ["foutmelding" "fouttekst"]) :content first)
        code (-> (xml-utils/get-in-xml response-element ["opleidingseenheidcode"]) :content first)]
    (merge {:goedgekeurd goedgekeurd, :response (xml-utils/format-xml (clj-xml/emit-str response-element))}
           (if goedgekeurd
             {:code code}
             {:errors errors}))))

(defn produce-output [rio-mode xml ooapi executor]
  (let [formatted-request (xml-utils/format-xml xml)]
    (case rio-mode
      "dry-run"         {:request formatted-request}
      "show-ooapi"      {:ooapi (json/write-str ooapi)}
      "execute"         (select-keys (executor) [:goedgekeurd :code])
      "execute-verbose" (let [{:keys [errors] :as resp} (executor)]
                          (merge {:request formatted-request}
                                 (if errors {:errors errors}
                                            (select-keys resp [:goedgekeurd :code])))))))

(defn -main [ooapi-mode rio-mode type id]
  (let [live-run (#{"execute" "execute-verbose"} rio-mode)
        credentials @xml-utils/dev-credentials
        bridge (case ooapi-mode "file" endpoints/ooapi-file-bridge
                                "local" (endpoints/ooapi-http-bridge-maker "http://localhost:8080/")
                                "demo04" (endpoints/ooapi-http-bridge-maker "http://demo04.test.surfeduhub.nl/")
                                "demo05" (endpoints/ooapi-http-bridge-maker "http://demo05.test.surfeduhub.nl/")
                                "demo06" (endpoints/ooapi-http-bridge-maker "http://demo06.test.surfeduhub.nl/")
                                "dev" (endpoints/ooapi-http-bridge-maker endpoints/ooapi-root-url))
        updater (if (= type "education-specification")
                  endpoints/education-specification-updated
                  endpoints/course-program-updated)
        rio-bridge (if live-run (make-rio-bridge credentials) (fn [_] {:code "1009O1234"}))
        {:keys [action rio-sexp errors ooapi]} (updater id (= type "course") bridge rio-bridge)]
    (if (some? errors)
      (prn errors)
      (let [xml (soap/prepare-soap-call action [rio-sexp] soap/beheren credentials)
            executor (fn [] (-> (xml-utils/post-body (:dev-url soap/beheren) xml soap/beheren action credentials)
                                (parse-response ,, action)))]
        (if (errors? xml)
          (println xml)
          (let [output (produce-output rio-mode xml ooapi executor)]
            (println (:request output))
            (prn (select-keys output [:errors :ooapi :goedgekeurd :code]))))))))
