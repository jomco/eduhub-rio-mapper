(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

(defn send-http-request [{:keys [method url content-type auth-opts] :as request}]
  (let [content-type (case content-type :json "application/json"
                                        :xml "text/xml; charset=utf-8")
        request (assoc request :content-type content-type
                               :throw-exceptions false
                               :keystore-type    "jks"
                               :trust-store-type "jks")
        auth-keys [:keystore :keystore-pass :trust-store :trust-store-pass :basic-auth]
        response (http/request (merge request (select-keys (or auth-opts {}) auth-keys)))]
    (log/debug (format "%s; %s; status %s" method url (response :status)))
    (assoc response :success (<= 200 (:status response) 299))))
