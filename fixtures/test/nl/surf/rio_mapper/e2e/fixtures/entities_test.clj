(ns nl.surf.rio-mapper.e2e.fixtures.entities-test
  (:require [nl.surf.rio-mapper.e2e.fixtures.entities :as sut]
            [nl.surf.rio-mapper.e2e.fixtures.ids :as ids]
            [clojure.test :refer [deftest is]])
  (:import java.util.UUID))

(def entity-fixtures
  (sut/fixtures))

(def nameset (:nameset entity-fixtures))

(def session (ids/mk-session))

(def course-id (ids/name->uuid nameset session "demo-course"))

(def program-id (ids/name->uuid nameset session "a-program"))

(deftest test-entities
  (let [course (sut/get-entity entity-fixtures :courses course-id)]
    (is course)
    (is (not= "demo-course" (:courseId course)))
    (let [programs (:programs course)]
      (is (not= ["a-program"] programs)
          "uuids generated for entity names")
      (is (= ["a-program"] (map #(ids/uuid->name nameset %) (:programs course)))))
    (is (sut/get-entity entity-fixtures :programs program-id))))
