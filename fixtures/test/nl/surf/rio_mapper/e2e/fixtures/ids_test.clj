(ns nl.surf.rio-mapper.e2e.fixtures.ids-test
  (:require [nl.surf.rio-mapper.e2e.fixtures.ids :as sut]
            [clojure.test :refer [deftest is]])
  (:import java.util.UUID))

(def names
  ["foo" "bar" "baz"])

(deftest roundtrip
  (let [nameset (sut/nameset names)
        session (sut/mk-session)]
    (doseq [name names]
      (let [uuid (sut/name->uuid nameset session name)]
        (is (uuid? uuid))
        (is (= name (sut/uuid->name nameset uuid)))
        (is (= session (sut/uuid->session uuid)))))))
