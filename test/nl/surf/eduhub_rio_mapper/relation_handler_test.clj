(ns nl.surf.eduhub-rio-mapper.relation-handler-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.relation-handler :as rh]))

(def education-specification (-> "fixtures/ooapi/education-specification.json"
                                 io/resource
                                 slurp
                                 (json/read-str :key-fn keyword)))

(deftest relation-differences
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

(defn- child [id parent-id valid-from & {:keys [] :as opts}]
  (merge opts
         {:educationSpecificationId id, :parent parent-id, :validFrom valid-from,
          :educationSpecificationType "program", :educationSpecificationSubType "variant"}))

(defn- parent [id children-ids valid-from & {:keys [] :as opts}]
  (merge opts
         {:educationSpecificationId id, :children children-ids, :validFrom valid-from,
          :educationSpecificationType "program"}))

(deftest after-upsert
  (let [job      {:institution-schac-home "a"}
        loader   {1 (child 1 2 "2022-01-01")
                  2 (parent 2 [1] "2022-01-01")
                  3 (parent 3 [4 5] "2022-01-01")
                  4 (child 4 3 "2022-01-01")
                  5 (child 5 3 "2022-01-01")}
        handlers {:resolver     (fn [id _oin]
                                  (case id
                                    1 "123O123"
                                    2 "223O123"
                                    3 "323O123"
                                    4 "423O123"
                                    5 "523O123"))
                  :ooapi-loader (fn [{::ooapi/keys [id]}] (loader id))
                  ; actual relations
                  :getter       (fn [_ _type code]
                                  (case code
                                    "123O123" []
                                    "223O123" []
                                    "323O123" []
                                    "423O123" []
                                    "523O123" []))
                  :mutate       identity}]
    (testing "child with one parent"
      (let [{:keys [missing superfluous]} (rh/after-upsert (loader 1) job handlers)]
        (is (empty? superfluous))
        (is (= missing #{{:valid-from "2022-01-01", :valid-to nil, :parent-opleidingseenheidcode "223O123", :child-opleidingseenheidcode "123O123"}}))))

    (testing "parent with one child"
      (let [{:keys [missing superfluous]} (rh/after-upsert (loader 2) job handlers)]
        (is (empty? superfluous))
        (is (= missing #{{:valid-from "2022-01-01", :valid-to nil, :parent-opleidingseenheidcode "223O123", :child-opleidingseenheidcode "123O123"}}))))

    (testing "parent with two children"
      (let [{:keys [missing superfluous]} (rh/after-upsert (loader 3) job handlers)]
        (is (empty? superfluous))
        (is (= missing #{{:valid-from "2022-01-01", :valid-to nil, :parent-opleidingseenheidcode "323O123", :child-opleidingseenheidcode "423O123"}
                         {:valid-from "2022-01-01", :valid-to nil, :parent-opleidingseenheidcode "323O123", :child-opleidingseenheidcode "523O123"}}))))))
