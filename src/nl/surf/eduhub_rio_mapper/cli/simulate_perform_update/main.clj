(ns nl.surf.eduhub-rio-mapper.cli.simulate-perform-update.main
  (:require
    [clojure.data.json :as json]
    [clojure.data.xml :as clj-xml]
    [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.rio.resolver :as resolver]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

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

(defn -main [ooapi-mode rio-mode type id & [institute-id]]
  (let [live-run (#{"execute" "execute-verbose"} rio-mode)
        credentials @xml-utils/dev-credentials
        bridge (case ooapi-mode
                 "file"
                 updated-handler/ooapi-file-bridge

                 "local"
                 (updated-handler/ooapi-http-bridge-maker "http://localhost:8080/")

                 "demo04"
                 (updated-handler/ooapi-http-bridge-maker "http://demo04.test.surfeduhub.nl/")


                 "demo05"
                 (updated-handler/ooapi-http-bridge-maker "http://demo05.test.surfeduhub.nl/")

                 "demo06"
                 (updated-handler/ooapi-http-bridge-maker "http://demo06.test.surfeduhub.nl/")

                 "gateway"
                 (updated-handler/ooapi-http-bridge-maker "https://gateway.test.surfeduhub.nl/" gateway-credentials)

                 "dev"
                 (updated-handler/ooapi-http-bridge-maker updated-handler/ooapi-root-url))
        resolver (if live-run (resolver/make-resolver credentials) (fn [_] {:code "1009O1234"}))
        updater (-> updated-handler/updated-handler
                    (updated-handler/wrap-load-entities bridge)
                    (updated-handler/wrap-resolver resolver))
        {:keys [action rio-sexp errors ooapi]} (updater {::ooapi/id id
                                                         ::ooapi/type type
                                                         ::ooapi/bridge bridge
                                                         ::rio/resolver resolver
                                                         :institute-id institute-id})]
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
