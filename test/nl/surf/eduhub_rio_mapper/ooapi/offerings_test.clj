(ns nl.surf.eduhub-rio-mapper.ooapi.offerings-test
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offr]
    [nl.surf.eduhub-rio-mapper.test-helper :refer [load-json]]))

(def course-offerings (load-json "fixtures/ooapi/course-offerings.json"))

(def program-offerings (load-json "fixtures/ooapi/program-offerings.json"))

(def program-offering-demo04 (load-json "fixtures/ooapi/program-demo04-offerings.json"))

(deftest validate-fixtures-explain-course-offerings
  (let [problems (get (s/explain-data ::offr/OfferingsRequest course-offerings)
                      :clojure.spec.alpha/problems)]
    (is (nil? problems))))

(deftest validate-fixtures-explain-program-offerings
  (let [problems (get (s/explain-data ::offr/OfferingsRequest program-offerings)
                      :clojure.spec.alpha/problems)]
    (is (nil? problems))))

(deftest validate-fixtures-explain-program-demo-offerings
  (let [problems (get (s/explain-data ::offr/OfferingsRequest program-offering-demo04)
                      :clojure.spec.alpha/problems)]
    (is (nil? problems))))
