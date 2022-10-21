(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

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

    (log/debug (format "%s; %s; status %s" method url (response :status)))
    (assoc response :success (<= 200 (:status response) 299))))
