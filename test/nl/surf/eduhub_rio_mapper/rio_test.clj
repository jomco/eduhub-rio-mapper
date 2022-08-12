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

(defn different-keys? [content]
  (when content
    (let [dkeys (count (filter identity (distinct (map :tag content))))
          n (count content)]
      (= dkeys n))))

(defn xml->json [element]
  (cond
    (nil? element) nil
    (string? element) element
    (sequential? element) (if (> (count element) 1)
                            (if (different-keys? element)
                              (reduce into {} (map (partial xml->json ) element))
                              (map xml->json element))
                            (xml->json  (first element)))
    (and (map? element) (empty? element)) {}
    (map? element) (if (:attrs element)
                     {(:tag element) (xml->json (:content element))
                      (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
                     {(:tag element) (xml->json  (:content element))})
    :else nil))

(defn collect-paths [el acc path include-atoms]
  (cond (map? el)
        (reduce (fn [a k] (collect-paths (k el) a (conj path k) include-atoms)) acc (keys el))

        (coll? el)
        (reduce (fn [a n] (collect-paths (nth el n) a (conj path n) include-atoms)) acc (range (count el)))

        :else
        (conj acc (if include-atoms [path el] path))))

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
  (let [credentials (xml-utils/credentials "test/keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")
        rio-sexp [[:duo:onderwijsaanbiedercode "110A133"]
                  [:duo:peildatum "2022-06-22"]
                  [:duo:pagina "0"]]
        volatile-paths-set (set volatile-paths)
        xml (soap/prepare-soap-call "opvragen_aangebodenOpleidingenVanOrganisatie" rio-sexp soap/raadplegen credentials)]
    ;; Are the non-volatile parts of the request unchanged?
    (is (= (vec (filter (fn [[path _]] (not (volatile-paths-set path))) (collect-paths (xml->json (xml/parse-str xml)) [] [] true)))
           (edn/read (PushbackReader. (io/reader (io/file "test/fixtures/rio/soap.edn"))))))
    ;; Do the two requests still differ in the same places?
    (let [[differences _ _] (data/diff (xml->json (xml/parse-str xml))
                             (xml->json (xml/parse-str (soap/prepare-soap-call "opvragen_aangebodenOpleidingenVanOrganisatie" rio-sexp soap/raadplegen credentials))))]
      (is (= (collect-paths differences [] [] false)
             volatile-paths)))))
