(ns nl.surf.eduhub-rio-mapper.api-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.http :as http]
            [nl.surf.eduhub-rio-mapper.api :as api]
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
      (let [{:keys [job status]} (api/routes (request :post path))]
        (is (= http/ok status))
        (is (= expected-job job)))

    {:action "upsert", :type "course", :id "31415"}
    "/job/upsert/courses/31415"

    {:action "upsert", :type "education-specification", :id "31415"}
    "/job/upsert/education-specifications/31415"

    {:action "upsert", :type "program", :id "31415"}
    "/job/upsert/programs/31415"

    {:action "delete", :type "course", :id "31415"}
    "/job/delete/courses/31415"

    {:action "delete", :type "education-specification", :id "31415"}
    "/job/delete/education-specifications/31415"

    {:action "delete", :type "program", :id "31415"}
    "/job/delete/programs/31415")

  (let [{:keys [status body]} (api/routes (request :get "/status/31415"))]
    (is (= http/not-found status))
    (is (= {:status :unknown} body))))
