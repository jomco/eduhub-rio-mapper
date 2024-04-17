;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.ooapi.course-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.specs.course :as crs]))

(def course (-> "fixtures/ooapi/course.json"
                io/resource
                slurp
                (json/read-str :key-fn keyword)))

(deftest validate-fixtures-explain
  (let [problems (get (s/explain-data ::crs/course course)
                      :clojure.spec.alpha/problems)]
    (is (nil? problems))))

(deftest validate-rio-consumer-missing-consumer
  (let [problems (s/explain-str ::crs/consumers [])]
    (is (= "[] - failed: not-empty spec: :nl.surf.eduhub-rio-mapper.specs.course/consumers\n" problems))))
