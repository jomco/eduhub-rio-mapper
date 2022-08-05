(ns nl.surf.eduhub-rio-mapper.ooapi.offerings-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offr]))

(def course-offering (-> "fixtures/ooapi/course-offerings.json"
                         io/resource
                         slurp
                         (json/read-str :key-fn keyword)))

(def program-offering (-> "fixtures/ooapi/program-offerings.json"
                io/resource
                slurp
                (json/read-str :key-fn keyword)))

(deftest validate-fixtures-explain-course-offerings
  (let [problems (get-in (s/explain-data ::offr/OfferingsRequest course-offering) [:clojure.spec.alpha/problems])]
    (is (nil? problems))))

(deftest validate-fixtures-explain-program-offerings
  (let [problems (get-in (s/explain-data ::offr/OfferingsRequest program-offering) [:clojure.spec.alpha/problems])]
    (is (nil? problems))))
