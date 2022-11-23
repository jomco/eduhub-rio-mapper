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
