(ns nl.surf.eduhub-rio-mapper.job-test
  (:require
    [clj-http.client :as client]
    [clojure.test :refer :all]
    [nl.jomco.http-status-codes :as http-status]
    [nl.surf.eduhub-rio-mapper.cli :as cli]
    [nl.surf.eduhub-rio-mapper.errors :as errors]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.test-helper :as helper])
  (:refer-clojure :exclude [run!]))

(def dummy-handlers {:delete! identity, :update! identity})

(def dummy-job {::ooapi/id 0, ::ooapi/type 0, :action "delete", :institution-schac-home 0, :institution-oin 0})

(def config
  {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
   :redis-key-prefix "eduhub-rio-mapper-test"
   :status-ttl-sec   10
   :worker           {:nap-ms        10
                      :retry-wait-ms 10
                      :queues        ["foo" "bar"]
                      :queue-fn      :queue
                      :retryable-fn  (constantly false)
                      :error-fn      (constantly false)
                      :set-status-fn (fn [_ _ & [_]] (comment "nop"))}})

(deftest run!
  (testing "throwing an exception"
    (let [msg      "yelp"
          handlers (assoc dummy-handlers :delete! (fn [_] (throw (Exception. msg))))]
      (is (errors/retryable? (job/run! handlers dummy-job))
          "throwing an exception results a retryable error")
      (is (= msg (-> (job/run! handlers dummy-job) :errors :message))
          "throwing an exception results a retryable error"))))

(deftest ^:redis webhook
  (testing "webhook"
    (let [last-seen-request-atom (atom nil)
          set-status-fn (cli/make-set-status-fn config)
          job {::job/callback-url "https://github.com/"
               ::job/resource     "course/123123"}
          mock-webhook  (fn mock-webhook [req]
                          (reset! last-seen-request-atom req)
                          {:status http-status/ok
                           :body   {:active    true
                                    :client_id "institution_client_id"}})]
      (binding [client/request mock-webhook]
        (set-status-fn job :done {:blabla {:opleidingseenheidcode "123"}})
        (helper/wait-while-predicate nil? last-seen-request-atom 1)
        (let [req @last-seen-request-atom]
          (is (= (:body req) {:status :done, :resource "course/123123", :attributes {:opleidingseenheidcode "123"}}))
          (is (= (:url req) "https://github.com/")))))))
