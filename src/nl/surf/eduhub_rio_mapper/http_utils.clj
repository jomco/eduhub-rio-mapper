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

(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context]))

;; middleware to add to the http client

;; Can be rebound dynamically; this happens a.o. in the smoketest.
(defn ^:dynamic *vcr* [handler request]
  (handler request))

(defn wrap-vcr [handler]
  (fn [request]
    (*vcr* handler request)))

(defn- wrap-outgoing-request-logging
  [handler]
  (fn [{:keys [method url headers] :as request}]
    (let [response (handler request)]
      (log/debugf "%s - %s; %s; status %s"
                  (get headers "traceparent")
                  method
                  url
                  (:status response))
      (log/trace {:request request :response response})
      response)))

(defn- add-request-options
  [{:keys [content-type] :as request}]
  {:pre [content-type]}
  (assoc request
         :content-type (case content-type
                         :json
                         "application/json"

                         :xml
                         "text/xml; charset=utf-8")
         :throw-exceptions false
         :keystore-type    "jks"
         :trust-store-type "jks"))

(defn- wrap-request-options
  [handler]
  (fn with-request-options
    [request]
    (handler (add-request-options request))))

(defn- wrap-errors
  [handler]
  (fn with-errors
    [request]
    (let [response (handler request)]
      (when-not (http-status/success-status? (:status response))
        (throw (ex-info "HTTP request failed"
                        {:request request, :response response})))
      response)))

(def ^{:arglists '([{:keys [url method] :as request}])} ;; set argument documention
  send-http-request
  "Sends an http request using `clj-http.client/request`.

  Takes a `request` map and returns a response."
  (-> (var http/request)                                    ; Can be changed dynamically
      wrap-vcr
      wrap-outgoing-request-logging
      wrap-request-options
      wrap-errors
      trace-context/wrap-new-trace-context))
