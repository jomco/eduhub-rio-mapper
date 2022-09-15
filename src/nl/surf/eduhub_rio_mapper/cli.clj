(ns nl.surf.eduhub-rio-mapper.cli
  (:require [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
            [nl.surf.eduhub-rio-mapper.rio.resolver :as resolver]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.jomco.envopts :as envopts]
            [environ.core :refer [env]]))

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
   :truststore-password ["Truststore password" :str]})

(defn -main
  [cmd institution-id type id]
  (when (not= cmd "updated")
    (println "Invalid command" cmd)
    (System/exit 1))
  (let [[{:keys [keystore keystore-password keystore-alias
                 truststore truststore-password
                 gateway-credentials
                 gateway-root-url]} errs] (envopts/opts env opts-spec)]
    (when errs
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description errs))
      (System/exit 1))
    (let [resolver (-> (xml-utils/credentials keystore
                                              keystore-password
                                              keystore-alias
                                              truststore
                                              truststore-password)
                       (resolver/make-resolver))
          ooapi-loader (updated-handler/ooapi-http-bridge-maker
                        gateway-root-url
                        gateway-credentials)
          handle-updated (-> updated-handler/updated-handler
                             (updated-handler/wrap-resolver resolver)
                             (updated-handler/wrap-load-entities ooapi-loader))]
      (prn (handle-updated {::ooapi/id id
                            ::ooapi/type type
                            :institution-id institution-id})))))
