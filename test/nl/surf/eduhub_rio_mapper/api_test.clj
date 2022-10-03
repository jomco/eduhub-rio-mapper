(ns nl.surf.eduhub-rio-mapper.api-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.http :as http]
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
                                     (assoc-in
                                      [:headers "x-schac-home"] "edu.nl")
                                     api/routes)]
        (is (= http/ok status))
        (is (= expected-job job)))

    {:action "upsert"
     :type "course"
     :id "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/courses/31415"

    {:action "upsert"
     :type "education-specification"
     :id "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/education-specifications/31415"

    {:action "upsert"
     :type "program"
     :id "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/programs/31415"

    {:action "delete"
     :type "course"
     :id "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/courses/31415"

    {:action "delete"
     :type "education-specification"
     :id "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/education-specifications/31415"

    {:action "delete"
     :type "program"
     :id "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/programs/31415")

  (let [{:keys [status body]} (api/routes (request :get "/status/31415"))]
    (is (= http/not-found status))
    (is (= {:status :unknown} body))))

(deftest wrap-job-queuer
  (let [queue-atom (atom [])
        app        (api/wrap-job-queuer identity #(swap! queue-atom conj %))]

    (is (= {} (app {}))
        "no job, do nothing")

    (let [res (app {:job                    {:test "dummy"}
                    :institution-schac-home "example.com"})]

      (is (-> res :body :token)
          "token returned")

      (is (= {:test                   "dummy"
              :institution-schac-home "example.com"}
             (-> @queue-atom first (dissoc :token)))
          "job queued")

      (is (-> @queue-atom first :token)
          "job has token"))))
