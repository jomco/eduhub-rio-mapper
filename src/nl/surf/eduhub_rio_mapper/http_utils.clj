(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

(defn send-http-request [{:keys [method url content-type auth-opts] :as all}]
  (let [request (select-keys all [:url :method :body :headers])
        content-type (case content-type :json "application/json"
                                        :xml "text/xml; charset=utf-8")
        http-opts (merge request {:content-type     content-type
                                  :throw-exceptions false
                                  :keystore-type    "jks"
                                  :trust-store-type "jks"})
        auth-keys [:keystore :keystore-pass :trust-store :trust-store-pass :basic-auth]
        response (http/request (merge http-opts (select-keys auth-opts auth-keys)))]
    (log/debug (format "%s; %s; status %s" method url (response :status)))
    (assoc response :success (<= 200 (:status response) 299))))

(defn get-http-request [url headers content-type auth-opts]
  (send-http-request {:url url
                      :method :get
                      :headers headers
                      :content-type content-type
                      :auth-opts auth-opts}))
