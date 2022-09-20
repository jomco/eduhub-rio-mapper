(ns nl.surf.eduhub-rio-mapper.cli
  (:require [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.loader :as loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.rio.resolver :as resolver]
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

(def actions
  #{"upsert" "delete"})

(defn -main
  [action institution-id type id]
  (when (not (actions action))
    (.println *err* (str "Invalid action '" action "'.\nValid actions are" actions))
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
          resolver (resolver/make-resolver rio-conf)
          mutate   (mutator/make-mutator rio-conf)
          handler (case action
                    "upsert"
                    (-> updated-handler/updated-handler
                        (updated-handler/wrap-resolver resolver)
                        (loader/wrap-load-entities (loader/make-ooapi-http-loader
                                                    gateway-root-url
                                                    gateway-credentials)))
                    "delete"
                    (-> updated-handler/deleted-handler
                        (updated-handler/wrap-resolver resolver)))]
      (prn (result->
            (handler {::ooapi/id      id
                      ::ooapi/type    type
                      :action         action
                      :institution-id institution-id})
            (mutate))))))
