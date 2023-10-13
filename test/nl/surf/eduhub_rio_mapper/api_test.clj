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
            [nl.surf.eduhub-rio-mapper.cli :as cli]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [ring.mock.request :refer [request]]))

(defn authenticated-request [method path]
  (assoc (request method path)
    :client-id 123
    :institution-oin "123"))

(deftest uuid-validation
  (is (= http-status/bad-request (:status (api/routes (authenticated-request :get "/status/invalid-token"))))))

(deftest routes
  (are [method path]
      (= http-status/bad-request (:status (api/routes (authenticated-request method path))))
    :get  "/status/1"
    :post "/job/upsert/courses/bleh"
    :post "/job/link/1234O4321/courses/abcdefgh-ijkl-mnop-qrst-uvwxyzabcdef")

  (are [method path]
      (is (= http-status/not-found (:status (api/routes (authenticated-request method path)))))
    :get  "/blerk"
    :get  "/job/upsert/courses/31415"
    :get  "/status"
    :post "/job/upsert/blerk/12345678-1234-2345-3456-123456789abc"
    :post "/job/blerk/courses/12345678-1234-2345-3456-123456789abc")

  (are [expected-job path]
      (let [{:keys [job status]} (-> :post
                                     (authenticated-request path)
                                     (assoc :institution-schac-home "edu.nl")
                                     api/routes)]
        (is (= http-status/ok status))
        (is (= expected-job job)))

    {:action                 "upsert"
     ::ooapi/type            "course"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/upsert/courses/12345678-1234-2345-3456-123456789abc"

    {:action                 "upsert"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/upsert/education-specifications/12345678-1234-2345-3456-123456789abc"

    {:action                 "upsert"
     ::ooapi/type            "program"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/upsert/programs/12345678-1234-2345-3456-123456789abc"

    {:action                 "delete"
     ::ooapi/type            "course"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/delete/courses/12345678-1234-2345-3456-123456789abc"

    {:action                 "delete"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/delete/education-specifications/12345678-1234-2345-3456-123456789abc"

    {:action                 "link"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     ::rio/opleidingscode    "1234O4321"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/link/1234O4321/education-specifications/12345678-1234-2345-3456-123456789abc"

    {:action                         "link"
     ::ooapi/type                    "course"
     ::ooapi/id                      "12345678-1234-2345-3456-123456789abc"
     ::rio/aangeboden-opleiding-code "1234O4321"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/link/1234O4321/courses/12345678-1234-2345-3456-123456789abc"

    {:action                         "link"
     ::ooapi/type                    "program"
     ::ooapi/id                      "12345678-1234-2345-3456-123456789abc"
     ::rio/aangeboden-opleiding-code "1234O4321"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/link/1234O4321/programs/12345678-1234-2345-3456-123456789abc"

    ;; Unlink is link to id with value `nil`
    {:action                 "link"
     ::ooapi/type            "education-specification"
     ::ooapi/id              nil
     ::rio/opleidingscode    "1234O4321"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/unlink/1234O4321/education-specifications"

    {:action                         "link"
     ::ooapi/type                    "course"
     ::ooapi/id                      nil
     ::rio/aangeboden-opleiding-code "1234O4321"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/unlink/1234O4321/courses"

    {:action                         "link"
     ::ooapi/type                    "program"
     ::ooapi/id                      nil
     ::rio/aangeboden-opleiding-code "1234O4321"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/unlink/1234O4321/programs"

    {:action                 "delete"
     ::ooapi/type            "program"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/delete/programs/12345678-1234-2345-3456-123456789abc")

  (is (= "12345678-1234-2345-3456-123456789abc"
         (-> (assoc (request :get "/status/12345678-1234-2345-3456-123456789abc")
               :client-id "123")
             (api/routes)
             :token))))

(deftest wrap-access-control
  (is (= http-status/forbidden
         (:status (api/routes (request :get "/status/12345678-1234-2345-3456-123456789abc"))))
      "no client, status request")

  (is (= http-status/ok
         (:status (api/routes (assoc (request :get "/status/12345678-1234-2345-3456-123456789abc") :client-id "ludwig"))))
      "read only client, status request")

  (is (= http-status/forbidden
         (:status (api/routes (assoc (request :post "/job/delete/courses/123") :client-id "ludwig"))))
      "read only client, mutation request")

  (is (= http-status/ok
         (:status (api/routes (assoc (request :get "/status/12345678-1234-2345-3456-123456789abc")
                                :client-id "wolfgang"
                                :institution-oin "123",
                                :institution-schac-home "uu.nl"))))
        "real client, status request")

  (is (= http-status/ok
         (:status (api/routes (assoc (request :post "/job/delete/courses/12345678-1234-2345-3456-123456789abc")
                                :client-id "wolfgang"
                                :institution-oin "123",
                                :institution-schac-home "uu.nl"))))
        "real client, mutation request"))

(deftest wrap-code-validator
  (let [app (api/wrap-code-validator identity)]

    (is (= {:status 400, :body "Invalid aangeboden opleidingcode 'foo'"}
           (app {:job {::rio/aangeboden-opleiding-code "foo"}}))
        "invalid aangeboden opleiding code")

    (let [res {:job {::rio/aangeboden-opleiding-code "12345678-1234-2345-3456-123456789abc"}}]
      (is (= res (app res))
          "valid aangeboden opleiding code"))

    (is (= {:status 400, :body "Invalid opleidingscode 'foo'"}
           (app {:job {::rio/opleidingscode "foo"}}))
        "invalid opleidingscode")

    (let [res {:job {::rio/opleidingscode "1234O4321"}}]
      (is (= res (app res))
          "valid opleidingscode"))))

(deftest metrics
  (let [app (api/wrap-metrics-getter api/routes (constantly {"foo" 1, "bar" 2}))
        {:keys [status body]} (app (request :get "/metrics"))]
    (is (= http-status/ok status))
    (is (= "rio_mapper_active_and_queued_job_count{schac_home=\"foo\"} 1\nrio_mapper_active_and_queued_job_count{schac_home=\"bar\"} 2"
           body))))

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

(deftest wrap-callback-extractor
  (let [app        (api/wrap-callback-extractor identity)]

    (is (= {} (app {}))
        "no callback, do nothing")

    (let [res (app {:job {} :headers {"x-callback" "dummy"}})]
      (is (= 400 (:status res))
          "illegal url"))

    (let [res (app {:job {} :headers {"x-callback" "https://surf.nl/"}})]
      (is (-> res :job ::job/callback-url)
          "valid url"))))

(deftest ^:redis wrap-status-getter
  (let [config      {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
                     :redis-key-prefix "eduhub-rio-mapper-test"
                     :status-ttl-sec   10}
        app         (api/wrap-status-getter identity config)
        status-set! (cli/make-set-status-fn config)]
    (status/purge! config)

    (status-set! {:token       "test-pending"
                  ::ooapi/type "test"
                  ::ooapi/id   "314"}
                 :pending)

    (status-set! {:token       "test-error"
                  ::ooapi/type "test"
                  ::ooapi/id   "3141"}
                 :error
                 {:errors
                  {:random  "crap"
                   :message "error"
                   :phase   "middle"}})

    (status-set! {:token       "test-done"
                  ::ooapi/type "test"
                  ::ooapi/id   "31415"}
                 :done
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
            :status http-status/not-found
            :body   {:status :unknown}}
           (app {:token "unknown"})))

    ;; test pending
    (is (= {:token  "test-pending"
            :status http-status/ok
            :body   {:status   :pending
                     :token    "test-pending"
                     :resource "test/314"}}
           (app {:token "test-pending"})))

    ;; test done status
    (is (= {:token  "test-done"
            :status http-status/ok
            :body   {:status     :done
                     :token      "test-done"
                     :resource   "test/31415"
                     :attributes {:opleidingseenheidcode "code"}}}
           (app {:token "test-done"})))

    ;; test error status
    (is (= {:token  "test-error"
            :status http-status/ok
            :body   {:status   :error
                     :token    "test-error"
                     :resource "test/3141"
                     :phase    "middle"
                     :message  "error"}}
           (app {:token "test-error"})))

    (status/purge! config)))
