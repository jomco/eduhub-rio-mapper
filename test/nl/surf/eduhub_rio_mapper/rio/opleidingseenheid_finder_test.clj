(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid-finder-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.dry-run :as dry-run]))

(defn generate-diff [a b] (dry-run/generate-diff-ooapi-rio {:rio-summary a :ooapi-summary b}))

(deftest generate-diff-ooapi-rio
  (is (= {}
         (generate-diff {} {})))
  (is (= {:fred {:diff false}}
         (generate-diff {:fred 1} {:fred 1})))
  (is (= {:fred   {:diff     true
                   :current  1
                   :proposed nil}
          :barney {:diff     true
                   :current  nil
                   :proposed 2}}
         (generate-diff {:fred 1} {:barney 2})))
  (is (= {:fred {:diff     true
                 :current  1
                 :proposed 2}}
         (generate-diff {:fred 1} {:fred 2}))))
