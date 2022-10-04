(ns nl.surf.eduhub-rio-mapper.cli
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.api.server :as api-server]
            [nl.surf.eduhub-rio-mapper.errors :as errors]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.oin-mapper :as oin-mapper]
            [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(def opts-spec
  {:gateway-user        ["OOAPI Gateway Username" :str
                         :in [:gateway-credentials :username]]
   :gateway-password    ["OOAPI Gateway Password" :str
                         :in [:gateway-credentials :password]]
   :gateway-root-url    ["OOAPI Gateway Root URL" :http]
   :keystore            ["Path to keystore" :file]
   :keystore-password   ["Keystore password" :str
                         :in [:keystore-pass]]              ; name compatibility with clj-http
   :keystore-alias      ["Key alias in keystore" :str]
   :truststore          ["Path to trust-store" :file
                         :in [:trust-store]]                ; name compatibility with clj-http
   :truststore-password ["Trust-store password" :str
                         :in [:trust-store-pass]]           ; name compatibility with clj-http
   :oin-mapping-path    ["Path to OIN mapping file" :file
                         :in [:oin-mapper-config :path]
                         :default "oin-mapping.json"]
   :rio-root-url        ["RIO Services Root URL" :http
                         :in [:rio-config :root-url]]
   :rio-recipient-oin   ["Recipient OIN for RIO SOAP calls" :str
                         :in [:rio-config :recipient-oin]]
   :api-port            ["HTTP port for serving web API" :int
                         :default 8080
                         :in [:api-config :port]]
   :api-hostname        ["Hostname for listing web API" :str
                         :default "localhost"
                         :in [:api-config :host]]
   :redis-uri           ["URI to redis" :str
                         :default "redis://localhost"
                         :in [:redis-conn :spec :uri]]
   :redis-password      ["Password to redis" :str
                         :default nil
                         :in [:redis-conn :spec :password]]
   :redis-key-prefix    ["Prefix for redis keys" :str
                         :default "eduhub-rio-mapper"
                         :in [:redis-key-prefix]]
   :status-ttl-sec      ["Number of seconds hours to keep job status" :int
                         :default (* 60 60 24 7) ;; one week
                         :in [:status-ttl-sec]]})

(def commands
  #{"upsert" "delete" "get" "resolve" "serve-api" "worker" "help"})

(defmethod envopts/parse :file
  [s _]
  (let [f (io/file s)]
    (if (.isFile f)
      [f]
      [nil (str "not a file: `" s "`")])))

(defn make-config
  []
  {:post [(some? (-> % :rio-config :credentials :certificate))]}
  (let [[{:keys [keystore
                 keystore-pass
                 keystore-alias
                 trust-store
                 trust-store-pass] :as config}
         errs] (envopts/opts env opts-spec)]
    (when errs
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description errs))
      (System/exit 1))
    (assoc-in config [:rio-config :credentials]
              (xml-utils/credentials keystore
                                     keystore-pass
                                     keystore-alias
                                     trust-store
                                     trust-store-pass))))

(defn make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials
           oin-mapper-config]}]
  (let [resolver       (rio.loader/make-resolver rio-config)
        oin-mapper     (oin-mapper/make-oin-mapper oin-mapper-config)
        getter         (rio.loader/make-getter rio-config)
        mutate         (mutator/make-mutator rio-config xml-utils/post-body)
        handle-updated (-> updated-handler/updated-handler
                           (updated-handler/wrap-resolver resolver)
                           (oin-mapper/wrap-oin-mapper oin-mapper)
                           (ooapi.loader/wrap-load-entities (ooapi.loader/make-ooapi-http-loader
                                                             gateway-root-url
                                                             gateway-credentials)))
        handle-deleted (-> updated-handler/deleted-handler
                           (updated-handler/wrap-resolver resolver)
                           (oin-mapper/wrap-oin-mapper oin-mapper))]
    {:handle-updated handle-updated
     :handle-deleted handle-deleted
     :mutate         mutate,
     :getter         getter
     :oin-mapper     oin-mapper
     :resolver       resolver}))

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

  (let [config           (make-config)
        {:keys [getter resolver oin-mapper]
         :as   handlers} (make-handlers config)
        queues           (oin-mapper/institution-schac-homes (:oin-mapper-config config))
        config           (assoc config
                                :worker {:queues        queues
                                         :queue-fn      :institution-schac-home
                                         :run-job-fn    (partial job/run! handlers)
                                         :set-status-fn (fn [job status & [data]]
                                                          (status/set! config (:token job) status data))
                                         :retryable-fn  errors/retryable?
                                         :error-fn      errors/errors?})]
    (case command
      "get"
      (let [[institution-schac-home & rest-args] args]
        (println (apply getter (oin-mapper institution-schac-home) rest-args)))

      "resolve"
      (let [[institution-schac-home id] args]
        (println (:code (resolver id (oin-mapper institution-schac-home)))))

      ("delete" "upsert")
      (let [[institution-schac-home type id] args]
        (println
         (json/write-str
          (job/run! handlers {:id                     id
                              :type                   type
                              :action                 command
                              :institution-schac-home institution-schac-home}))))

      "serve-api"
      (api-server/serve-api config)

      "worker"
      (worker/wait-worker
       (worker/start-worker! config)))))
