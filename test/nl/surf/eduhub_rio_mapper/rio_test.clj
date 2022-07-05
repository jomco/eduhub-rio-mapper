(ns nl.surf.eduhub-rio-mapper.rio-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.data.xml :as clj-xml]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.soap :as soap])
  (:import (java.io File ByteArrayInputStream)))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

;; NOTE You need keystore.jks in the root of the project (don't add to git, it's in .gitignore)
;; To create it: keytool -importkeystore -destkeystore keystore.jks -deststoretype pkcs12 -srckeystore keystore.p12 -srcstoretype pkcs12
;; To create keystore.p12: openssl pkcs12 -export -in rio_test_surfeduhub_surf_nl.pem -inkey rio_test_surfeduhub_surf_nl.key -out keystore.p12
(deftest print-signed-soap
  (when (.exists (File. "keystore.jks"))
    (-> education-specification nl.surf.eduhub-rio-mapper.rio/soap-str soap/signed-soap-str)))

(deftest print-signed-soap-prefab
  (when (.exists (File. "keystore.jks"))
    (as-> "test/opvragen_request.xml" subj
          (rio/wrap-file-in-soap subj)
          (soap/signed-soap-str subj)
          (spit "signed.xml" subj))))

(deftest dadada
  (spit "dada" (zip/xml-zip (clj-xml/parse (ByteArrayInputStream. (.getBytes (slurp "doc/soap-example.xml")))))))
