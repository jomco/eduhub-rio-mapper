(ns simulate-perform-update.main
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.ooapi.endpoints :as endpoints]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.soap :as soap]))

(defn -main [ooapi-mode rio-mode type id]
  (let [credentials (xml-utils/credentials "keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")
        bridge (case ooapi-mode "file" endpoints/ooapi-file-bridge
                                "local" (endpoints/ooapi-http-bridge-maker "http://localhost:8080/")
                                "demo04" (endpoints/ooapi-http-bridge-maker "http://demo04.test.surfeduhub.nl/")
                                "demo05" (endpoints/ooapi-http-bridge-maker "http://demo05.test.surfeduhub.nl/")
                                "demo06" (endpoints/ooapi-http-bridge-maker "http://demo06.test.surfeduhub.nl/")
                                "dev" (endpoints/ooapi-http-bridge-maker endpoints/ooapi-root-url))
        updater (case type "education-specification" endpoints/education-specification-updated
                           "program" endpoints/program-updated
                           "course" endpoints/course-updated)
        {:keys [action rio-sexp errors]} (updater id bridge)]
    (if (some? errors)
      (prn errors)
      (let [xml (soap/prepare-soap-call action [rio-sexp] soap/beheren credentials)]
        (if (= "dry-run" rio-mode)
          (println (xml-utils/format-xml xml))
          (prn (endpoints/parse-response (xml-utils/format-xml (xml-utils/post-body (:dev-url soap/beheren) xml (str (:contract soap/beheren) "/" action) credentials))
                                         action)))))))
