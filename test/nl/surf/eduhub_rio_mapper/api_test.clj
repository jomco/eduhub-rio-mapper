;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.api-test
  (:require [clojure.test :refer :all]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [ring.mock.request :refer [request]]))

(deftest routes
  (are [method path]
      (is (= http-status/not-found (:status (api/routes (request method path)))))
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
        (is (= http-status/ok status))
        (is (= expected-job job)))

    {:action                 "upsert"
     ::ooapi/type            "course"
     ::ooapi/id              "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/courses/31415"

    {:action                 "upsert"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/education-specifications/31415"

    {:action                 "upsert"
     ::ooapi/type            "program"
     ::ooapi/id              "31415"
     :institution-schac-home "edu.nl"}
    "/job/upsert/programs/31415"

    {:action                 "delete"
     ::ooapi/type            "course"
     ::ooapi/id              "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/courses/31415"

    {:action                 "delete"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "31415"
     :institution-schac-home "edu.nl"}
    "/job/delete/education-specifications/31415"

    {:action                 "delete"
     ::ooapi/type            "program"
     ::ooapi/id              "31415"
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
  (let [config {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
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
                  :datumTijdBedrijfsdocumentAttrs         {}})

    ;; without status
    (is (= {:token  "unknown"
            :status http-status/not-found
            :body   {:status :unknown}}
           (app {:token "unknown"})))

    ;; test pending
    (is (= {:token  "test-pending"
            :status http-status/ok
            :body   {:status :pending}}
           (app {:token "test-pending"})))

    ;; test done status
    (is (= {:token  "test-done"
            :status http-status/ok
            :body   {:status     :done
                     :attributes {:opleidingeenheidcode "code"}}}
           (app {:token "test-done"})))

    ;; test error status
    (is (= {:token  "test-error"
            :status http-status/ok
            :body   {:status  :error
                     :phase   "middle"
                     :message "error"}}
           (app {:token "test-error"})))

    (status/purge! config)))
