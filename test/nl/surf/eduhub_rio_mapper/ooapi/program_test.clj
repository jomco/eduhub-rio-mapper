(ns nl.surf.eduhub-rio-mapper.ooapi.program-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as prg]
            [nl.surf.eduhub-rio-mapper.ooapi.Program :as-alias Program]))

(def program (-> "fixtures/ooapi/program.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(def program-demo04 (-> "fixtures/ooapi/program-demo04.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(def consumers (-> "fixtures/ooapi/program-consumers.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(def other-consumer (first consumers))

(def rio-consumer (last consumers))

(deftest validate-rio-consumer
  (let [problems (get-in (s/explain-data ::Program/rio-consumer rio-consumer) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-other-consumer
  (let [problems (get-in (s/explain-data ::Program/other-consumer other-consumer) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-any-rio-consumer
  (let [problems (get-in (s/explain-data ::Program/consumer rio-consumer) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-any-other-consumer
  (let [problems (get-in (s/explain-data ::Program/consumer other-consumer) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-consumers
  (let [consumers [other-consumer rio-consumer]
        problems (get-in (s/explain-data ::Program/consumers consumers) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain
  (let [problems (get-in (s/explain-data ::prg/Program program) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain
  (let [problems (get-in (s/explain-data ::prg/Program program-demo04) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))
