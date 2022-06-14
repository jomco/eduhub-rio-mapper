(ns nl.surf.eduhub-rio-mapper-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper :as mapper]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.OpleidingsEenheid :as-alias rio.OpleidingsEenheid]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

;; prints the explanation if the default fixtures aren't valid
(deftest validate-fixtures-explain
  (let [problems (get-in (s/explain-data ::ooapi/EducationSpecification education-specification) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-name-required
  (let [value (dissoc education-specification :name)]
    (is (not (s/valid? ::ooapi/EducationSpecification value)))))

(deftest validate-fixtures-language-required-in-description
  (let [value (update-in education-specification [:description 0] dissoc :language)]
    (is (not (s/valid? ::ooapi/EducationSpecification value)))))

(deftest validate-fixtures-invalid-codetype
  (let [value (assoc-in education-specification [:primaryCode :codeType] "undefined")]
    (is (not (s/valid? ::ooapi/EducationSpecification value)))))

(deftest validate-fixtures-custom-codetype
  (is (s/valid? ::ooapi/EducationSpecification (assoc-in education-specification [:primaryCode :codeType] "x-undefined"))))

(deftest validate-invalid-value-in-top-level-attribute
  (doseq [[key invalid-codes] [[:fieldsOfStudy ["12345" "123a"]]
                               [:formalDocument ["medal"]]
                               [:level ["grandmaster"]]
                               [:sector ["culturele"]]
                               [:validFrom ["2022-31-12" "29-02-2020"]]
                               [:validTo ["2022-31-12" "29-02-2020"]]
                               [:educationSpecification ["123e4567-e89b-12d3-a456" "g23e4567-e89b-12d3-a456-426614174111"]]]]
    (doseq [invalid-code invalid-codes]
      (is (not (s/valid? ::ooapi/EducationSpecificationTopLevel (assoc-in education-specification [key] invalid-code)))))))

(deftest validate-fixtures-invalid-otherCodes-codetype
  (let [value (assoc-in education-specification [:otherCodes 0 :codeType] "undefined")]
    (is (not (s/valid? ::ooapi/EducationSpecification value)))))

(deftest validate-illegal-language-code-in-all-language-types-string-arrays
  (doseq [path [[:name 0 :language]
                [:description 0 :language]
                [:learningOutcomes 0 0 :language]]]
    (doseq [invalid-code [nil "" "-" "vrooom" "!" "e"]]
      (let [eduspec (assoc-in education-specification path invalid-code)]
        (is (not (s/valid? ::ooapi/EducationSpecification eduspec)))))))

(deftest validate-maxlength-abbreviation
  (is (not (s/valid? ::ooapi/EducationSpecification (assoc-in education-specification [:abbreviation] (apply str (repeat 257 "a")))))))

(deftest validate-maxlength-link
  (is (not (s/valid? ::ooapi/EducationSpecification (assoc-in education-specification [:link] (apply str (repeat 2049 "a")))))))

(deftest translate-EducationSpecification
  (testing "translating fixtures"
    (is (= {::rio/type                         ::rio/HoOpleiding,
            ::rio.OpleidingsEenheid/beginDatum "2019-08-24",
            ::rio.OpleidingsEenheid/eindDatum  "2019-08-24",
            ::rio.OpleidingsEenheid/soort      "VARIANT"}
           (mapper/translate-EducationSpecification education-specification)))
    (is (s/valid? ::rio/HoOpleiding (mapper/translate-EducationSpecification education-specification)))))
