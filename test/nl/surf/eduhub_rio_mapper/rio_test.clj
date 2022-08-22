(ns nl.surf.eduhub-rio-mapper.rio-test
  (:require [clojure.data :as data]
            [clojure.data.json :as json]
            [clojure.data.xml :as xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils])
  (:import (java.io PushbackReader)))

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

(defn collect-paths
  "If leaf-node, add current path (and node if include-leaves is true) to acc.
   Otherwise, call recursively for each child with name of child appended to the path."
  [node acc path include-leaves]
  {:pre [(or (not (map? node))
             (every? keyword? (keys node)))]}
  (cond (map? node)
        (reduce (fn [a k] (collect-paths (k node) a (conj path k) include-leaves)) acc (keys node))

        (coll? node)
        (reduce (fn [a n] (collect-paths (nth node n) a (conj path n) include-leaves)) acc (range (count node)))

        :else
        (conj acc (if include-leaves [path node] path))))

(def volatile-paths [[:Envelope :Body :opvragen_aangebodenOpleidingenVanOrganisatie_request :datumTijdBedrijfsdocument]
                     [:Envelope :Body :opvragen_aangebodenOpleidingenVanOrganisatie_request :identificatiecodeBedrijfsdocument]
                     [:Envelope :Header :Security :Timestamp :Expires]
                     [:Envelope :Header :Security :Timestamp :Created]
                     [:Envelope :Header :Security :Signature :KeyInfo :SecurityTokenReference :ReferenceAttrs :URI]
                     [:Envelope :Header :Security :Signature :SignatureValue]
                     [:Envelope :Header :Security :Signature :SignedInfo 0]
                     [:Envelope :Header :Security :Signature :SignedInfo 1]
                     [:Envelope :Header :Security :Signature :SignedInfo 2]
                     [:Envelope :Header :Security :Signature :SignedInfo 3]
                     [:Envelope :Header :Security :Signature :SignedInfo 4]
                     [:Envelope :Header :Security :Signature :SignedInfo 5 :Reference :DigestValue]
                     [:Envelope :Header :Security :Signature :SignedInfo 6 :Reference :DigestValue]
                     [:Envelope :Header :Security :Signature :SignedInfo 7 :Reference :DigestValue]
                     [:Envelope :Header :Security :BinarySecurityTokenAttrs :wsu/Id]
                     [:Envelope :Header :MessageID]])

;; Differences between two consecutive signings of identical requests should be in timestamps, uuids and digests.
(deftest only-differences-between-signed-requests-are-in-given-paths
  (let [credentials @xml-utils/test-credentials
        rio-sexp [[:duo:onderwijsaanbiedercode "110A133"]
                  [:duo:peildatum "2022-06-22"]
                  [:duo:pagina "0"]]
        volatile-paths-set (set volatile-paths)
        xml (soap/prepare-soap-call "opvragen_aangebodenOpleidingenVanOrganisatie" rio-sexp soap/raadplegen credentials)]
    ;; Are the non-volatile parts of the request unchanged?
    (is (= (vec
             (filter
               (fn [[path _]] (not (volatile-paths-set path)))
               (collect-paths (xml-utils/xml->edn (xml/parse-str xml)) [] [] true)))
           (edn/read (PushbackReader. (io/reader (io/file "test/fixtures/rio/soap.edn"))))))
    ;; Do the two requests still differ in the same places?
    (let [[differences _ _] (data/diff (xml-utils/xml->edn (xml/parse-str xml))
                                       (xml-utils/xml->edn (xml/parse-str (soap/prepare-soap-call "opvragen_aangebodenOpleidingenVanOrganisatie" rio-sexp soap/raadplegen credentials))))]
      (is (= (collect-paths differences [] [] false)
             volatile-paths)))))
