(ns nl.surf.eduhub-rio-mapper.cli
  (:require [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio.resolver :as resolver]
            [nl.surf.eduhub-rio-mapper.rio.upserter :as rio.upserter]
            [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(def opts-spec
  {:gateway-user        ["OOAPI Gateway Username" :str
                         :in [:gateway-credentials :username]]
   :gateway-password    ["OOAPI Gateway Password" :str
                         :in [:gateway-credentials :password]]
   :gateway-root-url    ["OOAPI Gateway Root URL" :http]
   :keystore            ["Path to keystore" :file]
   :keystore-password   ["Keystore password" :str]
   :keystore-alias      ["Key alias in keystore" :str]
   :truststore          ["Path to truststore" :file]
   :truststore-password ["Truststore password" :str]
   :rio-root-url        ["RIO Services Root URL" :http
                         :in [:rio :root-url]]
   :rio-recipient-oin   ["Recipient OIN for RIO SOAP calls" :str
                         :in [:rio :recipient-oin]]
   :rio-sender-oin      ["Sender OIN for RIO SOAP calls" :str
                         :in [:rio :sender-oin]]})

(defn -main
  [action institution-id type id]
  (when (not= action "upsert")
    (println "Invalid action" action)
    (System/exit 1))
  (let [[{:keys [keystore
                 keystore-password
                 keystore-alias
                 truststore
                 truststore-password
                 gateway-credentials
                 gateway-root-url
                 rio]} errs] (envopts/opts env opts-spec)]
    (when errs
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description errs))
      (System/exit 1))
    (let [rio-conf (assoc rio
                          :credentials (xml-utils/credentials
                                        keystore
                                        keystore-password
                                        keystore-alias
                                        truststore
                                        truststore-password))
          resolver        (resolver/make-resolver rio-conf)
          ooapi-loader    (updated-handler/ooapi-http-bridge-maker
                           gateway-root-url
                           gateway-credentials)
          handle-updated  (-> updated-handler/updated-handler
                              (updated-handler/wrap-resolver resolver)
                              (updated-handler/wrap-load-entities ooapi-loader))
          upsert          (rio.upserter/make-upserter rio-conf)]
      (prn (result->
            (handle-updated {::ooapi/id      id
                             ::ooapi/type    type
                             :action         action
                             :institution-id institution-id})
            (upsert))))))
