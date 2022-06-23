(ns nl.surf.eduhub-rio-mapper.ooapi.program-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as prg])
  (:require [clojure.test :refer :all]))

(def program (-> "fixtures/ooapi/program.json"
                 io/resource
                 slurp
                 (json/read-str :key-fn keyword)))

(deftest validate-fixtures-explain
  (let [problems (get-in (s/explain-data ::prg/Program program) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))
