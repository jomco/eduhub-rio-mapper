(ns nl.surf.eduhub-rio-mapper.rio-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

(deftest canonicalization-and-digestion
  (let [canonicalizer (fn [id] (str "<wsa:Action "
                                    (soap/xmlns [["duo" (:schema soap/raadplegen)]
                                            ["soapenv" soap/soap-envelope]
                                            ["wsa" soap/ws-addressing]
                                            ["wsu" soap/wsu-schema]])
                                    " wsu:Id=\""
                                    id
                                    "\">"
                                    (:contract soap/raadplegen)
                                    "/opvragen_aangebodenOpleidingenVanOrganisatie</wsa:Action>"))

        expected-digest "u95macy7enN9aTCyQKuQqTIsYj/8G9vv8o6EBV1OZjs="]
    (is (= expected-digest (xml-utils/digest-sha256 (canonicalizer "id-629A9B11E252AF76D61657184053301145"))))))

(deftest print-signed-soap-prefab-aangeboden-opleidingen-van-organisatie
  (let [body [:duo:opvragen_aangebodenOpleidingenVanOrganisatie_request {:xmlns:duo (:contract soap/raadplegen)}
              [:duo:identificatiecodeBedrijfsdocument "4628374628"]
              [:duo:verzendendeInstantie "0000000700025BE00000"]
              [:duo:ontvangendeInstantie "00000001800866472000"]
              [:duo:datumTijdBedrijfsdocument "2022-06-22T12:21:26.958260"]
              [:duo:onderwijsaanbiedercode "110A133"]
              [:duo:peildatum "2022-06-22"]
              [:duo:pagina "0"]]]
    (is (soap/convert-to-signed-dom-document body soap/raadplegen "opvragen_aangebodenOpleidingenVanOrganisatie" "test/keystore.jks" "xxxxxx" "test-surf")
        "Should not raise exceptions")))
