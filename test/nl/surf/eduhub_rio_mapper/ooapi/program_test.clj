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
  (let [{::s/keys [problems]} (s/explain-data ::Program/rio-consumer rio-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-other-consumer
  (let [{::s/keys [problems]} (s/explain-data ::Program/other-consumer other-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-any-rio-consumer
  (let [{::s/keys [problems]} (s/explain-data ::Program/consumer rio-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-any-other-consumer
  (let [{:s/keys [problems]} (s/explain-data ::Program/consumer other-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-consumers
  (let [{::s/keys [problems]} (s/explain-data ::Program/consumers [other-consumer rio-consumer])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain
  (let [{::s/keys [problems]} (s/explain-data ::prg/Program program)]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain-demo04
  (let [{::s/keys [problems]} (s/explain-data ::prg/Program program-demo04)]
    (is (contains? #{nil []} problems))))
