(ns nl.surf.eduhub-rio-mapper.cli
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.errors :as errors]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
            [nl.surf.eduhub-rio-mapper.relation-handler :as relation-handler]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils])
  (:gen-class))

(def opts-spec

  {:clients-info-path                  ["CLients info config file" :file
                                        :in [:clients-info-config :path]]
   :gateway-user                       ["OOAPI Gateway Username" :str
                                        :in [:gateway-credentials :username]]
   :gateway-password                   ["OOAPI Gateway Password" :str
                                        :in [:gateway-credentials :password]]
   :gateway-root-url                   ["OOAPI Gateway Root URL" :http]
   :keystore                           ["Path to keystore" :file]
   :keystore-password                  ["Keystore password" :str
                                        :in [:keystore-pass]]              ; name compatibility with clj-http
   :keystore-alias                     ["Key alias in keystore" :str]
   :truststore                         ["Path to trust-store" :file
                                        :in [:trust-store]]                ; name compatibility with clj-http
   :truststore-password                ["Trust-store password" :str
                                        :in [:trust-store-pass]]           ; name compatibility with clj-http
   :rio-root-url                       ["RIO Services Root URL" :http
                                        :in [:rio-config :root-url]]
   :rio-recipient-oin                  ["Recipient OIN for RIO SOAP calls" :str
                                        :in [:rio-config :recipient-oin]]
   :surf-conext-introspection-endpoint ["SurfCONEXT introspection endpoint" :http
                                        :in [:auth-config :introspection-endpoint]]
   :surf-conext-client-id              ["SurfCONEXT client id for Mapper service" :str
                                        :in [:auth-config :client-id]]
   :surf-conext-client-secret          ["SurfCONEXT client secret for Mapper service" :str
                                        :in [:auth-config :client-secret]]
   :api-port                           ["HTTP port for serving web API" :int
                                        :default 8080
                                        :in [:api-config :port]]
   :api-hostname                       ["Hostname for listing web API" :str
                                        :default "localhost"
                                        :in [:api-config :host]]
   :redis-uri                          ["URI to redis" :str
                                        :default "redis://localhost"
                                        :in [:redis-conn :spec :uri]]
   :redis-key-prefix                   ["Prefix for redis keys" :str
                                        :default "eduhub-rio-mapper"
                                        :in [:redis-key-prefix]]
   :status-ttl-sec                     ["Number of seconds hours to keep job status" :int
                                        :default (* 60 60 24 7) ;; one week
                                        :in [:status-ttl-sec]]})
(def commands
  #{"upsert" "delete" "get" "show" "resolve" "serve-api" "worker" "help"})

(defmethod envopts/parse :file
  [s _]
  (let [f (io/file s)]
    (if (.isFile f)
      [f]
      [nil (str "not a file: `" s "`")])))

(defn- make-config
  []
  {:post [(some? (-> % :rio-config :credentials :certificate))]}
  (let [[{:keys [clients-info-config
                 keystore
                 keystore-pass
                 keystore-alias
                 trust-store
                 trust-store-pass] :as config}
         errs] (envopts/opts env opts-spec)]
    (when errs
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description errs))
      (System/exit 1))
    (-> config
        (assoc-in [:rio-config :credentials]
                  (xml-utils/credentials keystore
                                      keystore-pass
                                      keystore-alias
                                      trust-store
                                      trust-store-pass))
        (assoc :clients (clients-info/read-clients-data clients-info-config)))))

(defn- extract-eduspec-from-result [result]
  (let [entity (:ooapi result)]
    (when (= "aanleveren_opleidingseenheid" (:action result))
      entity)))

(defn- update-and-mutate [handle-updated {:keys [mutate] :as handlers} job]
  {:pre [(job :institution-oin) (job :institution-schac-home)]}
  (errors/when-result [result (handle-updated job)
                       mutate-result (mutate result)
                       eduspec (extract-eduspec-from-result result)]
    (relation-handler/after-upsert eduspec job handlers)
    mutate-result))

(defn- extract-opleidingscode-from-job [resolver {::ooapi/keys [id] :keys [institution-oin]}]
  (resolver id institution-oin))

(defn- delete-and-mutate [handle-deleted {:keys [mutate resolver] :as handlers} job]
  (errors/result-> (relation-handler/delete-relations (extract-opleidingscode-from-job resolver job)
                                                      (:institution-oin job)
                                                      handlers)
                   (handle-deleted job)
                   (mutate)))

(defn- make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials]}]
  (let [resolver       (rio.loader/make-resolver rio-config)
        getter         (rio.loader/make-getter rio-config)
        mutate         (mutator/make-mutator rio-config http-utils/send-http-request)
        ooapi-loader   (ooapi.loader/make-ooapi-http-loader
                         gateway-root-url
                         gateway-credentials)
        basic-handlers {:ooapi-loader      ooapi-loader
                        :mutate            mutate
                        :getter            getter
                        :resolver          resolver}
        handle-updated (-> updated-handler/update-mutation
                           (partial updated-handler/resolve-id resolver)
                           (partial ooapi.loader/load-entities ooapi-loader))
        handle-deleted (-> updated-handler/deletion-mutation
                           (partial updated-handler/resolve-id resolver))
        update-and-mutate (partial update-and-mutate handle-updated basic-handlers)
        delete-and-mutate (partial delete-and-mutate handle-deleted basic-handlers)]
    (assoc basic-handlers
      :update-and-mutate update-and-mutate
      :delete-and-mutate delete-and-mutate)))

(defn -main
  [command & args]
  (when (not (commands command))
    (.println *err* (str "Invalid command '" command "'."))
    (.println *err* (str "Valid commands are: " (string/join ", " commands)))
    (System/exit 1))

  (when (= command "help")
    (println (str "Available commands: " (string/join ", " commands) "."))
    (println "Configuration settings via environment:\n")
    (println (envopts/specs-description opts-spec))
    (System/exit 0))

  (let [{:keys [clients] :as config} (make-config)
        {:keys [getter resolver ooapi-loader]
         :as   handlers}             (make-handlers config)
        queues                       (clients-info/institution-schac-homes clients)
        config                       (assoc config
                                            :worker {:queues        queues
                                                     :queue-fn      :institution-schac-home
                                                     :run-job-fn    (partial job/run! handlers)
                                                     :set-status-fn (fn [job status & [data]]
                                                                      (status/set! config (:token job) status data))
                                                     :retryable-fn  errors/retryable?
                                                     :error-fn      errors/errors?})]
    (case command
      "serve-api"
      (api/serve-api config)

      "worker"
      (worker/wait-worker
        (worker/start-worker! config))

      (let [[client-id & args] args
            client-info (clients-info/client-info clients client-id)]
        (when (nil? client-info)
          (.println *err* (str "No client info found for client id " client-id))
          (System/exit 1))

        (case command
          "get"
          (let [result (apply getter (:institution-oin client-info) args)]
            (if (string? result) (println result)
                                 (pprint result)))

          "show"
          (let [[type id] args]
            (prn (ooapi-loader (merge client-info {::ooapi/id id ::ooapi/type type}))))

          "resolve"
          (let [[id] args]
            (println (resolver id (:institution-oin client-info))))

          ("delete" "upsert")
          (let [[type id & remaining] args
                result (job/run! handlers (merge {:id     id
                                                  :type   type
                                                  :action command
                                                  :args   remaining}
                                                 client-info))]
            (if (errors/errors? result)
              (binding [*out* *err*]
                (prn result))
              (-> result json/write-str println))))))))
