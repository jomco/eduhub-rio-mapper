(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [nl.jomco.http-status-codes :as http-status]))

(defn send-http-request
  [{:keys [content-type method url] :as request}]
  {:pre [url method content-type]}
  (let [response (-> request
                     (assoc :content-type (case content-type
                                            :json
                                            "application/json"

                                            :xml
                                            "text/xml; charset=utf-8")
                            :throw-exceptions false
                            :keystore-type    "jks"
                            :trust-store-type "jks")
                     http/request)]


    (log/debugf "%s; %s; status %s" method url (:status response))
    (log/trace {:request request :response response})
    (assoc response :success (http-status/success-status? (:status response)))))
