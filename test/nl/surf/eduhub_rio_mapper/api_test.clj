(ns nl.surf.eduhub-rio-mapper.api-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.http :as http]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [ring.mock.request :refer [request]]))

(deftest routes
  (are [method path]
      (is (= http/not-found (:status (api/routes (request method path)))))
    :get  "/blerk"
    :get  "/job/upsert/courses/31415"
    :get  "/status"
    :post "/job/upsert/blerk/31415"
    :post "/job/blerk/courses/31415")

  (are [expected-job path]
      (let [{:keys [job status]} (-> :post
                                     (request path)
                                     (assoc :institution-schac-home "edu.nl")
                                     api/routes)]
        (is (= http/ok status))
        (is (= expected-job job)))

    {:action                 "upsert"
     :type                   "course"
     :id                     "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/courses/31415"

    {:action                 "upsert"
     :type                   "education-specification"
     :id                     "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/education-specifications/31415"

    {:action                 "upsert"
     :type                   "program"
     :id                     "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/programs/31415"

    {:action                 "delete"
     :type                   "course"
     :id                     "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/courses/31415"

    {:action                 "delete"
     :type                   "education-specification"
     :id                     "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/education-specifications/31415"

    {:action                 "delete"
     :type                   "program"
     :id                     "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/programs/31415")

  (is (= "31415"
         (-> :get (request "/status/31415") (api/routes) :token))))

(deftest wrap-job-queuer
  (let [queue-atom (atom [])
        app        (api/wrap-job-enqueuer identity #(swap! queue-atom conj %))]

    (is (= {} (app {}))
        "no job, do nothing")

    (let [res (app {:job {:test "dummy"}})]
      (is (-> res :body :token)
          "token returned")

      (is (= {:test "dummy"}
             (-> @queue-atom first (dissoc :token)))
          "job queued")

      (is (-> @queue-atom first :token)
          "job has token")

      (is (= (-> @queue-atom first :token)
             (-> res :body :token))
          "job token same as returned token"))))

(deftest ^:redis wrap-status-getter
  (let [config {:redis-conn       {:pool {} :spec {:uri "redis://localhost"}}
                :redis-key-prefix "eduhub-rio-mapper-test"
                :status-ttl-sec   10}
        app    (api/wrap-status-getter identity config)]
    (status/purge! config)

    (status/set! config "test-pending" :pending
                 {:foo     "bar"
                  :message "error"
                  :phase   "middle"})

    (status/set! config "test-error" :error
                 {:random  "crap"
                  :message "error"
                  :phase   "middle"})

    (status/set! config "test-done" :done
                 {:aanleveren_opleidingseenheid_response
                  {:opleidingseenheidcodeAttrs             {},
                   :verzendendeInstantie                   "...",
                   :opleidingseenheidcode                  "code",
                   :identificatiecodeBedrijfsdocumentAttrs {},
                   :requestGoedgekeurdAttrs                {},
                   :ontvangendeInstantieAttrs              {},
                   :verzendendeInstantieAttrs              {},
                   :requestGoedgekeurd                     "true",
                   :identificatiecodeBedrijfsdocument      "...",
                   :datumTijdBedrijfsdocument              "...",
                   :ontvangendeInstantie                   "...",
                   :datumTijdBedrijfsdocumentAttrs         {}}})

    ;; without status
    (is (= {:token  "unknown"
            :status http/not-found
            :body   {:status :unknown}}
           (app {:token "unknown"})))

    ;; test pending
    (is (= {:token  "test-pending"
            :status http/ok
            :body   {:status :pending}}
           (app {:token "test-pending"})))

    ;; test done status
    (is (= {:token  "test-done"
            :status http/ok
            :body   {:status     :done
                     :attributes {:opleidingeenheidcode "code"}}}
           (app {:token "test-done"})))

    ;; test error status
    (is (= {:token  "test-error"
            :status http/ok
            :body   {:status  :error
                     :phase   "middle"
                     :message "error"}}
           (app {:token "test-error"})))

    (status/purge! config)))
