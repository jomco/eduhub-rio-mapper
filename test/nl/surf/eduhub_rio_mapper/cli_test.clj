(ns nl.surf.eduhub-rio-mapper.cli-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.cli :as cli]))

(deftest test-blocking-retry
  (let [retry-3-times #(cli/blocking-retry % [0.001 0.001 0.001] nil)]
    (testing "first attempt successful"
      (let [atm (atom 1)]
        (is (= true (retry-3-times #(do (swap! atm dec)
                                     (= 0 @atm)))))))
    (testing "no successful attempts"
      (let [atm (atom 5)]
        (is (nil? (retry-3-times #(do (swap! atm dec)
                                     (= 0 @atm)))))))
    (testing "third attempt successful"
      (let [atm (atom 3)]
        (is (= true (retry-3-times #(do (swap! atm dec)
                                     (= 0 @atm)))))))))
