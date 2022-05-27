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
                                 (json/read-str {:key-fn keyword})))

(deftest validate-fixtures
  (is (s/valid? ::ooapi/EducationSpecification education-specification)))

(deftest translate-EducationSpecification
  (testing "translating fixtures"
    (is (= {::rio/type                         ::rio/HoOpleiding,
            ::rio.OpleidingsEenheid/beginDatum "2019-08-24",
            ::rio.OpleidingsEenheid/eindDatum  "2019-08-24",
            ::rio.OpleidingsEenheid/soort      "VARIANT"}
           (mapper/translate-EducationSpecification education-specification)))
    (is (s/valid? ::rio/HoOpleiding (mapper/translate-EducationSpecification education-specification)))))
