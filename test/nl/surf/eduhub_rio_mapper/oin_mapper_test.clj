(ns nl.surf.eduhub-rio-mapper.oin-mapper-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.oin-mapper :as oin-mapper]))

(deftest test-make-oin-mapper
  (let [mapper (oin-mapper/make-oin-mapper (io/file "test/test-oin-mapping.json"))]
    (is (= "0000000700025BE00000"
           (mapper "demo06.test.surfeduhub.nl")))))
