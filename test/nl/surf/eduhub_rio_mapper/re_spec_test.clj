(ns nl.surf.eduhub-rio-mapper.re-spec-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.re-spec :as sut]))

(deftest test-dangerous-codes
  (is (sut/without-dangerous-codes? "foobar < thing"))
  (is (sut/without-dangerous-codes? "foobar &lt; thing"))
  (is (not (sut/without-dangerous-codes? "foo<bar")))
  (is (not (sut/without-dangerous-codes? "foo&lt;bar"))))
