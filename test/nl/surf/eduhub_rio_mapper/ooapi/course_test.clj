(ns nl.surf.eduhub-rio-mapper.ooapi.course-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as rio-ahoo]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as crs]))

(def course (-> "fixtures/ooapi/course.json"
                io/resource
                slurp
                (json/read-str :key-fn keyword)))

(deftest validate-fixtures-explain
  (let [problems (get-in (s/explain-data ::crs/Course course) [:clojure.spec.alpha/problems])]
    (is (nil? problems))))

(deftest validate-conversion-to-rio
  (let [rio-obj (rio-ahoo/convert-from-course course)
        problems (get-in (s/explain-data ::rio-ahoo/AangebodenHoOpleidingsonderdeel rio-obj) [:clojure.spec.alpha/problems])]
    (is (nil? problems))))
