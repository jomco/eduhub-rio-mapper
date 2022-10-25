(ns nl.surf.eduhub-rio-mapper.relation-handler-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.relation-handler :as rh]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

(deftest test-relations
  (testing "parent no existing relations"
    (let [actual-relations #{}
          {:keys [missing superfluous]} (rh/relation-differences
                                          (assoc education-specification :rio-code "234O432")
                                          :parent
                                          [(assoc education-specification :rio-code "654O456")]
                                          actual-relations)]
      (is (= missing #{{:parent-opleidingseenheidcode "234O432", :child-opleidingseenheidcode "654O456", :valid-from "2019-08-24", :valid-to "2019-08-24"}}))
      (is (= superfluous #{}))))
  (testing "parent with existing relations"

    (let [actual-relations #{{:parent-opleidingseenheidcode "234O432", :child-opleidingseenheidcode "654O456", :valid-from "2019-08-24", :valid-to "2019-08-24"}}
          {:keys [missing superfluous]} (rh/relation-differences
                                          (assoc education-specification :rio-code "234O432")
                                          :parent
                                          [(assoc education-specification :rio-code "654O456")]
                                          actual-relations)]
      (is (= missing #{}))
      (is (= superfluous #{}))))

  (testing "parent with existing relations different start date"
    (let [actual-relations #{{:parent-opleidingseenheidcode "234O432", :child-opleidingseenheidcode "654O456", :valid-from "2011-08-24", :valid-to "2019-08-24"}}
          {:keys [missing superfluous]} (rh/relation-differences
                                          (assoc education-specification :rio-code "234O432")
                                          :parent
                                          [(assoc education-specification :rio-code "654O456")]
                                          actual-relations)]
      (is (= missing #{(assoc (first actual-relations) :valid-from "2019-08-24")}))
      (is (= superfluous actual-relations)))))
