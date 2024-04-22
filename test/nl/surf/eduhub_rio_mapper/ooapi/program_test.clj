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

(ns nl.surf.eduhub-rio-mapper.ooapi.program-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.specs.program :as prg]))

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
  (let [{::s/keys [problems]} (s/explain-data ::prg/rio-consumer rio-consumer)]
    (is (contains? #{nil []} problems))))

(deftest validate-rio-consumer-missing-consumer
  (let [problems (s/explain-str ::prg/consumers [])]
    (is (= "[] - failed: not-empty spec: :nl.surf.eduhub-rio-mapper.specs.program/consumers\n" problems))))

(deftest validate-rio-consumer-wrong-education-offerer-code
  (let [{::s/keys [problems]} (s/explain-data ::prg/rio-consumer (assoc rio-consumer :educationOffererCode "123B123"))]
    (is (= :educationOffererCode (-> problems first :path first)))))

(deftest validate-consumers
  (let [{::s/keys [problems]} (s/explain-data ::prg/consumers [other-consumer rio-consumer])]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain
  (let [{::s/keys [problems]} (s/explain-data ::prg/program program)]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-duration-optional-explain
  (let [{::s/keys [problems]} (s/explain-data ::prg/program (dissoc program :duration))]
    (is (contains? #{nil []} problems))))

(deftest validate-fixtures-explain-demo04
  (let [{::s/keys [problems]} (s/explain-data ::prg/program program-demo04)]
    (is (contains? #{nil []} problems))))
