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
                                     (assoc-in [:headers "x-schac-home"] "edu.nl")
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
    (status/set! config "test" :test {:foo :bar})

    ;; without status
    (is (= {:token  "unknown"
            :status http/not-found
            :body   {:status :unknown}}
           (app {:token "unknown"})))

    ;; with status
    (is (= {:token  "test"
            :status http/ok
            :body   {:status :test
                     :foo    :bar}}
           (app {:token "test"})))

    (status/purge! config)))
