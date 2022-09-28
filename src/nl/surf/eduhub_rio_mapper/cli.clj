(ns nl.surf.eduhub-rio-mapper.cli
  (:require [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
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
  #{"upsert" "delete" "get" "resolve"})

(defn make-handlers []
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
    (let [rio-conf       (assoc rio :credentials
                                (xml-utils/credentials keystore
                                                       keystore-password
                                                       keystore-alias
                                                       truststore
                                                       truststore-password))
          resolver       (rio.loader/make-resolver rio-conf)
          getter         (rio.loader/make-getter rio-conf)
          mutate         (mutator/make-mutator rio-conf)
          handle-updated (-> updated-handler/updated-handler
                             (updated-handler/wrap-resolver resolver)
                             (ooapi.loader/wrap-load-entities (ooapi.loader/make-ooapi-http-loader
                                                               gateway-root-url
                                                               gateway-credentials)))
          handle-deleted (-> updated-handler/deleted-handler
                             (updated-handler/wrap-resolver resolver))]
      {:handle-updated handle-updated
       :handle-deleted handle-deleted
       :mutate         mutate,
       :getter         getter
       :resolver       resolver})))

(defn -main
  [action & args]
  (when (not (actions action))
    (.println *err* (str "Invalid action '" action "'.\nValid actions are" actions))
    (System/exit 1))
  (let [{:keys [mutate
                getter
                handle-updated
                handle-deleted
                resolver]} (make-handlers)]
    (case action
      "get"
      (println (getter args))

      "resolve"
      (let [[id] args]
        (println (:code (resolver id))))

      ("delete" "upsert")
      (let [[institution-id type id] args]
        (println (result->
                  ((case action
                     "delete" handle-deleted
                     "upsert" handle-updated)
                   {::ooapi/id      id
                    ::ooapi/type    type
                    :action         action
                    :institution-id institution-id})
                  (mutate)))))))
