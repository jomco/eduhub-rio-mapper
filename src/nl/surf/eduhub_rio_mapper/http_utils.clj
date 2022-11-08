(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context]))

;; middleware to add to the http client

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
  (-> http/request
      wrap-outgoing-request-logging
      wrap-request-options
      wrap-errors
      trace-context/wrap-new-trace-context))
