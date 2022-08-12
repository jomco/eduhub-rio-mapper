(ns simulate-perform-update.main
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.ooapi.endpoints :as endpoints]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.soap :as soap]))

(defn debug [x] (println x) x)

(defn- single-xml-unwrapper [element tag]
  (-> element
      (xml-utils/get-in-dom [tag])
      (.getFirstChild)
      (.getTextContent)))

(defn handle-rio-identificatiecode-response [element]
  (let [goedgekeurd (single-xml-unwrapper element "ns2:requestGoedgekeurd")]
    (when (= "true" goedgekeurd)
      (single-xml-unwrapper element "ns2:opleidingseenheidcode"))))

(defn make-rio-bridge [credentials]
  (fn [ooapi-id]
    (if (nil? ooapi-id)
      nil
      (let [soap-action (str (:contract soap/raadplegen) "/opvragen_rioIdentificatiecode")
            xml (soap/prepare-soap-call "opvragen_rioIdentificatiecode"
                                        [[:duo:eigenOpleidingseenheidSleutel ooapi-id]]
                                        soap/raadplegen
                                        credentials)]
        (-> (xml-utils/post-body (:dev-url soap/raadplegen) xml soap-action credentials)
            (debug)
            (xml-utils/xml->dom)
            (.getDocumentElement)
            (xml-utils/get-in-dom,, ["SOAP-ENV:Body" "ns2:opvragen_rioIdentificatiecode_response"])
            (handle-rio-identificatiecode-response))))))

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
            executor (fn [] (-> (xml-utils/post-body (:dev-url soap/beheren) xml (str (:contract soap/beheren) "/" action) credentials)
                                (endpoints/parse-response ,, action)
                                prn))]
        (case rio-mode
          "dry-run" (println "XML REQUEST" (xml-utils/format-xml xml))
          "show-ooapi" (print (json/write-str ooapi))
          "execute" (executor)
          "execute-verbose" (do (println "XML REQUEST" (xml-utils/format-xml xml))
                                (executor)))))))
