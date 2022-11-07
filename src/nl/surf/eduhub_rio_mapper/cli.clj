(ns nl.surf.eduhub-rio-mapper.cli
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
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
            [nl.surf.eduhub-rio-mapper.rio :as rio]
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
  #{"upsert" "delete" "delete-by-code" "get" "show" "resolve" "serve-api" "worker" "help"})

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

(defn blocking-retry
  "Calls f and retries if it returns nil.

  Sleeps between each invocation as specified in retry-delays-seconds.
  Returns return value of f when successful.
  Returns nil when as many retries as delays have taken place. "
  [f retry-delays-seconds action]
  (loop [retry-delays-seconds retry-delays-seconds]
    (or
      (f)
      (when-not (empty? retry-delays-seconds)
        (let [[head & tail] retry-delays-seconds]
          (log/warn (format "%s failed - sleeping for %s seconds." action head))
          (Thread/sleep (long (* 1000 head)))
          (recur tail))))))

(defn- make-update-and-mutate [handle-updated {:keys [mutate resolver] :as handlers}]
  (fn [{::ooapi/keys [id type] :keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (errors/when-result [result (handle-updated job)
                         mutate-result (mutate result)
                         _ (or (not= "education-specification" type)
                               ;; ^^-- skip check for courses and
                               ;; programs, since resolver doesn't
                               ;; work for them yet
                               (blocking-retry #(resolver id institution-oin)
                                                 [30 120 600]
                                                 "Ensure upsert is processed by RIO")
                             {:errors "Entity not found in RIO after upsert."})
                         eduspec (extract-eduspec-from-result result)]
                        (when eduspec
                          (relation-handler/after-upsert eduspec job handlers))
                        mutate-result)))

(defn- make-delete-and-mutate [handle-deleted {:keys [mutate resolver] :as handlers}]
  (fn [{::ooapi/keys [id type] ::rio/keys [opleidingscode] :keys [institution-oin] :as job}]
    (if (= type "education-specification")
      (when-let [opleidingscode (or opleidingscode (resolver id institution-oin))]
        (errors/when-result [_      (relation-handler/delete-relations opleidingscode type institution-oin handlers)
                             result (handle-deleted job)]
                            (mutate result)))
      (errors/result-> job
                       (handle-deleted)
                       mutate))))

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
                           (updated-handler/wrap-resolver resolver)
                           (ooapi.loader/wrap-load-entities ooapi-loader))
        handle-deleted (updated-handler/wrap-resolver updated-handler/deletion-mutation resolver)
        update-and-mutate (make-update-and-mutate handle-updated basic-handlers)
        delete-and-mutate (make-delete-and-mutate handle-deleted basic-handlers)]
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

          ("delete" "upsert" "delete-by-code")
          (let [[type id & remaining] args
                delete-by-code (= "delete-by-code" command)
                name-id (if delete-by-code :opleidingscode :id)
                result (job/run! handlers (assoc client-info
                                            name-id         id
                                            :type           type
                                            :action         (if delete-by-code "delete" command)
                                            :args           remaining))]
            (if (errors/errors? result)
              (binding [*out* *err*]
                (prn result))
              (-> result json/write-str println))))))))
