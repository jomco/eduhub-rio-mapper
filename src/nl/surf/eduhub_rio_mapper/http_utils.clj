(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

(defn send-http-request [url method request-body headers content-type auth-opts]
  (let [http-opts {:url              url
                   :method           method
                   :headers          headers
                   :body             request-body
                   :content-type     (case content-type :json "application/json"
                                                        :xml "text/xml; charset=utf-8")
                   :throw-exceptions false
                   :keystore-type    "jks"
                   :trust-store-type "jks"}
        auth-keys [:keystore :keystore-pass :trust-store :trust-store-pass :basic-auth]
        response (http/request (merge http-opts (select-keys auth-opts auth-keys)))]
    (log/debug (format "%s; %s; status %s" method url (response :status)))
    (assoc response :success (<= 200 (:status response) 299))))

(defn get-http-request [url headers content-type auth-opts]
  (send-http-request url :get nil headers content-type auth-opts))
