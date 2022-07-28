(ns nl.surf.eduhub-rio-mapper.ooapi.education-specification-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as es]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

(deftest validate-no-problems-in-fixtures
  (let [problems (get-in (s/explain-data ::es/EducationSpecification education-specification) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-name-required
  (let [value (dissoc education-specification :name)]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-fixtures-language-required-in-description
  (let [value (update-in education-specification [:description 0] dissoc :language)]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-fixtures-invalid-codetype
  (let [value (assoc-in education-specification [:primaryCode :codeType] "undefined")]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-fixtures-custom-codetype
  (is (s/valid? ::es/EducationSpecification (assoc-in education-specification [:primaryCode :codeType] "x-undefined"))))

(deftest validate-invalid-value-in-top-level-attribute
  (doseq [[key invalid-codes] [[:fieldsOfStudy ["12345" "123a"]]
                               [:formalDocument ["medal"]]
                               [:level ["grandmaster"]]
                               [:sector ["culturele"]]
                               [:validFrom ["2022-31-12" "29-02-2020"]]
                               [:validTo ["2022-31-12" "29-02-2020"]]
                               [:parent ["123e4567-e89b-12d3-a456" "g23e4567-e89b-12d3-a456-426614174111"]]]]
    (doseq [invalid-code invalid-codes]
      (is (not (s/valid? ::es/EducationSpecificationTopLevel (assoc-in education-specification [key] invalid-code)))))))

(deftest validate-fixtures-invalid-otherCodes-codetype
  (let [value (assoc-in education-specification [:otherCodes 0 :codeType] "undefined")]
    (is (not (s/valid? ::es/EducationSpecification value)))))

(deftest validate-illegal-language-code-in-all-language-types-string-arrays
  (doseq [path [[:name 0 :language]
                [:description 0 :language]
                [:learningOutcomes 0 0 :language]]]
    (doseq [invalid-code [nil "" "-" "vrooom" "!" "e"]]
      (let [eduspec (assoc-in education-specification path invalid-code)]
        (is (not (s/valid? ::es/EducationSpecification eduspec)))))))

(deftest validate-maxlength-abbreviation
  (is (not (s/valid? ::es/EducationSpecification (assoc-in education-specification [:abbreviation] (apply str (repeat 257 "a")))))))

(deftest validate-maxlength-link
  (is (not (s/valid? ::es/EducationSpecification (assoc-in education-specification [:link] (apply str (repeat 2049 "a")))))))
