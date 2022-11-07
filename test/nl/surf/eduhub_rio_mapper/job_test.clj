(ns nl.surf.eduhub-rio-mapper.job-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.errors :as errors]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi])
  (:refer-clojure :exclude [run!]))

(def dummy-handlers {:delete-and-mutate identity, :update-and-mutate identity})

(def dummy-job {::ooapi/id 0, ::ooapi/type 0, :action "delete", :institution-schac-home 0, :institution-oin 0})

(deftest run!
  (testing "throwing an exception"
    (let [msg      "yelp"
          handlers (assoc dummy-handlers :delete-and-mutate (fn [_] (throw (Exception. msg))))]
      (is (errors/retryable? (job/run! handlers dummy-job))
          "throwing an exception results a retryable error")
      (is (= msg (-> (job/run! handlers dummy-job) :errors :message))
          "throwing an exception results a retryable error"))))
