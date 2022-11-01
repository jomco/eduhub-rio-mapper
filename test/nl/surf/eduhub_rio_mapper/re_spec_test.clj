(ns nl.surf.eduhub-rio-mapper.re-spec-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.re-spec :as sut]))

(deftest test-dangerous-codes
  (is (not (sut/looks-like-html? "foobar < thing")))
  (is (sut/looks-like-html? "foobar &lt; thing"))
  (is (sut/looks-like-html? "foo<bar"))
  (is (sut/looks-like-html? "foo&lt;bar"))
  (is (sut/looks-like-html? "foo&lt;bar"))
  (is (sut/looks-like-html? "foo &#34; bar")))
