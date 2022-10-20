(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

(defn send-http-request
  [{:keys [content-type method url] :as request}]
  (let [content-type (case content-type :json "application/json"
                                        :xml "text/xml; charset=utf-8")
        request (assoc request :content-type content-type
                               :throw-exceptions false
                               :keystore-type    "jks"
                               :trust-store-type "jks")
        response (http/request request)]
    (log/debug (format "%s; %s; status %s" method url (response :status)))
    (assoc response :success (<= 200 (:status response) 299))))
