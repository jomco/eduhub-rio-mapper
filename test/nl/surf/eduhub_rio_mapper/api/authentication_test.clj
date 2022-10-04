(ns nl.surf.eduhub-rio-mapper.api.authentication-test
  (:require [clj-http.client :as client]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.api.authentication :as authentication]
            [nl.surf.eduhub-rio-mapper.http :as http]))

(deftest test-bearer-token
  (is (nil?
       (authentication/bearer-token
        {:headers {"authorization" "Bearerfoobar"}})))
  (is (= "foobar"
         (authentication/bearer-token
          {:headers {"authorization" "Bearer foobar"}}))))

(def valid-token
  (str "eyJraWQiOiJrZXlfMjAyMl8xMF8wNF8wMF8wMF8wMF8wMDEiLCJ0eXAiOiJK"
       "V1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiJwbGF5Z3JvdW5kX2NsaWVudCI"
       "sInN1YiI6InBsYXlncm91bmRfY2xpZW50IiwibmJmIjoxNjY0ODkyNTg0LCJ"
       "pc3MiOiJodHRwczpcL1wvY29ubmVjdC50ZXN0LnN1cmZjb25leHQubmwiLCJ"
       "leHAiOjE2NjU3NTY1ODQsImlhdCI6MTY2NDg5MjU4NCwianRpIjoiNTlkNGY"
       "yZDQtZmRhOC00MTBjLWE2MzItY2QzMzllMTliNTQ2In0.nkQqZK02SamkNI2"
       "ICDrE1LxN6kBBDOwQd5zU9BsPxNIfOwP1qnCwNQELo5xX0R2cJJJqCgmq8nw"
       "BjZ4xNba4lTS8dii4Fmy-8u7fN427mx-_G-GoCGQSKQD6OdVKjDsRMJX4rHN"
       "DSg5HhtDz5or-2Xp_H0Vi0mWMOBgQGjfbjLjJJZ1T0rlaZbq-_ZAatb2dFcr"
       "WliqbFrous_fSPo4jrbPVHYunF-wZZoLZFlOaCyJM24A_3Mrv4JPw78WRnyu"
       "ZG0H7aS2v_KLe5Xh2lUkSa0lkO_xP2uhQQ_69bnmF0RQiKe9vVDi7mhi0aGE"
       "do2f-iJ8JQj4EwPzZkSvdJt569w"))


(def count-calls (atom 0))

;; Mock out the introspection endpoint. Pretend token is active if
;; it's equal to `valid-token`, invalid otherwise.
(defn mock-introspection
  [{:keys [form-params]}]
  (swap! count-calls inc)
  (if (= valid-token (:token form-params))
    {:status http/ok
     :body {:active true
            :client_id "institution_client_id"}}
    {:status http/ok
     :body {:active false}}))

(deftest token-validator
  ;; This binds the *dynamic* http client in clj-http.client
  (reset! count-calls 0)
  (binding [client/request mock-introspection]
    (let [authenticator (-> {:introspection-endpoint "https://example.com"
                             :client-id              "foo"
                             :client-secret          "bar"}
                            (authentication/make-token-authenticator)
                            (authentication/cache-token-authenticator {:ttl-minutes 1}))
          handler       (-> (fn [req]
                              {:status http/ok
                               :body   {:client (:client-id req)}})
                      (authentication/wrap-authentication authenticator))]
      (is (= {:status http/ok
              :body   {:client "institution_client_id"}}
             (handler {:headers {"authorization" (str "Bearer " valid-token)}}))
          "Ok when valid token provided")

      (is (= http/unauthorized
             (:status (handler {})))
          "Unauthorized when no token provided")

      (is (= http/forbidden
             (:status (handler {:headers {"authorization" (str "Bearer invalid-token")}})))
          "Forbidden with invalid token")

      (is (= 2 @count-calls)
          "Correct number of calls to introspection-endpoint")

      (reset! count-calls 0)
      (is (= {:status http/ok
              :body   {:client "institution_client_id"}}
             (handler {:headers {"authorization" (str "Bearer " valid-token)}}))
          "CACHED: Ok when valid token provided")

      (is (= 0 @count-calls)
          "No more calls to introspection-endpoint"))))
