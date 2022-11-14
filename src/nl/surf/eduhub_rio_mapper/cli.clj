(ns nl.surf.eduhub-rio-mapper.cli
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.errors :as errors]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.keystore :as keystore]
            [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.processing :as processing]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker])
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
                                        :in [:keystore-pass]] ; name compatibility with clj-http
   :keystore-alias                     ["Key alias in keystore" :str]
   :truststore                         ["Path to trust-store" :file
                                        :in [:trust-store]] ; name compatibility with clj-http
   :truststore-password                ["Trust-store password" :str
                                        :in [:trust-store-pass]] ; name compatibility with clj-http
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

(def final-status? #{:done :error :time-out})

(def callback-retry-sleep-ms 30000)

(defmethod envopts/parse :file
  [s _]
  (let [f (io/file s)]
    (if (.isFile f)
      [f]
      [nil (str "not a file: `" s "`")])))

(defn make-config
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
                  (keystore/credentials keystore
                                        keystore-pass
                                        keystore-alias
                                        trust-store
                                        trust-store-pass))
        (assoc :clients (clients-info/read-clients-data clients-info-config)))))

(defn parse-args-getter [[type id & [pagina]]]
  (let [[type response-type] (reverse (str/split type #":" 2))
        response-type (and response-type (keyword response-type))]
    (assert (rio.loader/valid-get-actions type))
    (-> (when pagina {:pagina pagina})
        (assoc (if (= type "opleidingsrelatiesBijOpleidingseenheid") ::rio/opleidingscode ::ooapi/id) id
               :response-type response-type
               ::rio/type type))))

(defn- do-async-callback [{::job/keys [callback-url status resource opleidingseenheidcode]}]
  (let [attrs (when opleidingseenheidcode {:attributes {:opleidingseenheidcode opleidingseenheidcode}})
        body  (merge attrs {:status status :resource resource})
        req   {:url callback-url :method :post :content-type :json :body body}]
    (async/thread
      (loop [retries-left 3]
        (when-not (http-status/success-status? (-> req http-utils/send-http-request :status))
          (log/warnf "Could not reach webhook %s" (:url req))
          (Thread/sleep callback-retry-sleep-ms)
          (when (pos? retries-left)
            (recur (dec retries-left))))))))

(defn make-set-status-fn [config]
  ; data is result of run-job-fn, which is result of job/run!, which is result of update-and-mutate or delete-and-mutate
  ; which is the result of the mutator/make-mutator, which is the result of handle-rio-mutate-response, which
  ; is the parsed xml response converted to edn
  (fn [{::job/keys [callback-url] :as job} status & [xml-resp]]
    (let [data (-> xml-resp vals first)]
      (status/set! config (:token job) status data)
      (when (and callback-url (final-status? status))
        (do-async-callback (assoc job ::job/status status
                                      ::job/opleidingseenheidcode (:opleidingseenheidcode data)))))))

(defn -main
  [command & args]
  (when (not (commands command))
    (.println *err* (str "Invalid command '" command "'."))
    (.println *err* (str "Valid commands are: " (str/join ", " commands)))
    (System/exit 1))

  (when (= command "help")
    (println (str "Available commands: " (str/join ", " commands) "."))
    (println "Configuration settings via environment:\n")
    (println (envopts/specs-description opts-spec))
    (System/exit 0))

  (let [{:keys [clients] :as config} (make-config)
        {:keys [getter resolver ooapi-loader]
         :as   handlers} (processing/make-handlers config)
        queues (clients-info/institution-schac-homes clients)
        config (assoc config
                 :worker {:queues        queues
                          :queue-fn      :institution-schac-home
                          :run-job-fn    (partial job/run! handlers)
                          :set-status-fn (make-set-status-fn config)
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
          (let [result (getter (assoc (parse-args-getter args)
                                 :institution-oin (:institution-oin client-info)))]
            (if (string? result) (println result)
                                 (pprint result)))

          "show"
          (let [[type id] args]
            (prn (ooapi-loader (merge client-info {::ooapi/id id ::ooapi/type type}))))

          "resolve"
          (let [[id] args]
            (println (resolver id (:institution-oin client-info))))

          ("upsert" "delete" "delete-by-code")
          (let [[type id & remaining] args
                name-id (if (= "delete-by-code" command) ::rio/opleidingscode ::ooapi/id)
                job     (assoc client-info
                          name-id id
                          ::ooapi/type type
                          :action (if (= "delete-by-code" command) "delete" command)
                          :args remaining)
                result  (job/run! handlers job)]
            (if (errors/errors? result)
              (binding [*out* *err*]
                (prn result))
              (-> result json/write-str println))))))))
