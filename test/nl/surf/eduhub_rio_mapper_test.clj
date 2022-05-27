(ns nl.surf.eduhub-rio-mapper-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper :as mapper]
            [nl.surf.eduhub-rio-mapper.rio :as-alias rio]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str {:key-fn keyword})))

(deftest translate-EducationSpecification
  (testing "translating fixtures"
    (is (= {::rio/type ::rio/HoOpleiding}
           (mapper/translate-EducationSpecification education-specification)))))
