(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context]))

(defn send-http-request
  [{:keys [content-type method url] :as request}]
  {:pre [url method content-type]}
  (let [request (-> request
                     (assoc :content-type (case content-type
                                            :json
                                            "application/json"

                                            :xml
                                            "text/xml; charset=utf-8")
                            :throw-exceptions false
                            :keystore-type    "jks"
                            :trust-store-type "jks")
                     ;; Create a new trace-context from the current one
                     ;; in scope, and add it to the request
                     (trace-context/set-context (trace-context/new-context)))
        response  (http/request request)]
    (log/debugf "%s - %s; %s; status %s"
                (get-in request [:headers "traceparent"])
                method
                url
                (:status response))
    (log/trace {:request request :response response})
    (assoc response :success (http-status/success-status? (:status response)))))
