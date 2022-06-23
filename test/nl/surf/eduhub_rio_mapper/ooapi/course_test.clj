(ns nl.surf.eduhub-rio-mapper.ooapi.course-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as crs]))

(def course (-> "fixtures/ooapi/course.json"
                io/resource
                slurp
                (json/read-str :key-fn keyword)))

(deftest validate-fixtures-explain
  (let [problems (get-in (s/explain-data ::crs/Course course) [:clojure.spec.alpha/problems])]
    (is (contains? #{nil []} problems))))
