(ns nl.surf.eduhub-rio-mapper.http-utils
  (:require
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

(defn post-body
  [url request-body contract action credentials]
  (let [timestamp (System/currentTimeMillis)]
    (log/debug "request" action timestamp request-body)
    (let [http-opts {:headers          {"SOAPAction" (str contract "/" action)}
                     :body             request-body
                     :content-type     "text/xml; charset=utf-8"
                     :throw-exceptions false
                     :keystore-type    "jks"
                     :trust-store-type "jks"}
          credential-keys [:keystore :keystore-pass :trust-store :trust-store-pass]
          {:keys [body status] :as response} (as-> (select-keys credentials credential-keys) $
                                                   (merge http-opts $)
                                                   (http/post url $)
                                                   (select-keys $ [:body :status]))]
      (log/info (format "POST %s %s %s" url action status))
      (log/debug "response" action timestamp body)
      (assoc response :success (<= 200 status 299))         ; TODO return this instead
      body)))
