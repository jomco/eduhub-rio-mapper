;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.cli
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :as trace-context]
            [nl.surf.eduhub-rio-mapper.api :as api]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.keystore :as keystore]
            [nl.surf.eduhub-rio-mapper.logging :as logging]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.processing :as processing]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker])
  (:gen-class))

(defn parse-int-list [s & _opts] [(mapv #(Integer/parseInt %) (str/split s #","))])

(def opts-spec

  {:clients-info-path                  ["Clients info config file" :file
                                        :in [:clients-info-config :path]]
   :connection-timeout-millis          ["HTTP connection timeout in milliseconds" :int
                                        :default 10000
                                        :in [:rio-config :connection-timeout]]
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
   :rio-read-url                       ["RIO Services Read URL" :str
                                        :in [:rio-config :read-url]]
   :rio-update-url                     ["RIO Services Update URL" :str
                                        :in [:rio-config :update-url]]
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
   :job-retry-wait-ms                  ["Number of ms to wait before retrying job" :int
                                        :default 5000
                                        :in [:worker :retry-wait-ms]]
   :job-max-retries                    ["Max number of retries of a job" :int
                                        :default 3
                                        :in [:worker :max-retries]]
   :redis-uri                          ["URI to redis" :str
                                        :default "redis://localhost"
                                        :in [:redis-conn :spec :uri]]
   :redis-key-prefix                   ["Prefix for redis keys" :str
                                        :default "eduhub-rio-mapper"
                                        :in [:redis-key-prefix]]
   :rio-retry-attempts-seconds         ["Number of seconds to wait for first, second, etc. retry of RIO command, comma separated" :int-list
                                        :default [5,30,120,600]
                                        :parser parse-int-list
                                        :in [:rio-config :rio-retry-attempts-seconds]]
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
    (assert (rio.loader/valid-get-types type))
    (-> (when pagina {:pagina pagina})
        (assoc (if (= type "opleidingsrelatiesBijOpleidingseenheid") ::rio/opleidingscode ::ooapi/id) id
               :response-type response-type
               ::rio/type type))))

(defn- do-async-callback [config {:keys [token] :as job}]
  (let [status (status/get config token)
        req    {:url                    (::job/callback-url job)
                :method                 :post
                :content-type           :json
                :institution-schac-home (:institution-schac-home job)
                :body                   (json/json-str status)
                :connection-timeout     (-> config :rio-config :connection-timeout-millis)
                :throw-exceptions       false}]
    (async/thread
      (trace-context/with-context (:trace-context job)
        (logging/with-mdc (assoc (:trace-context job)
                            :token                  (:token job)
                            :url                    (::job/callback-url job)
                            :institution-schac-home (:institution-schac-home job))
          (try
            (loop [retries-left 3]
              (let [status (-> req http-utils/send-http-request :status)]
                (when-not (http-status/success-status? status)
                  (log/debugf "Could not reach webhook %s, %d retries left" (:url req) retries-left)
                  (Thread/sleep callback-retry-sleep-ms)
                  (when (pos? retries-left)
                    (recur (dec retries-left))))))
            (catch Exception ex
              (logging/log-exception ex nil))))))))

(defn make-set-status-fn [config]
  (fn [{::job/keys [callback-url] :keys [token] :as job}
       status & [data]]
    (let [opleidingseenheidcode (-> data :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
      (status/set! config
                   token
                   (cond-> {:status   status
                            :token    token
                            :resource (str (::ooapi/type job) "/" (::ooapi/id job))}

                           (and (= :done status)
                                opleidingseenheidcode)
                                ;; Data is result of run-job-fn, which is result of
                                ;; job/run!, which is result of update-and-mutate
                                ;; or delete-and-mutate which is the result of the
                                ;; mutator/make-mutator, which is the result of
                                ;; handle-rio-mutate-response, which is the parsed
                                ;; xml response converted to edn.
                           (assoc :attributes {:opleidingseenheidcode opleidingseenheidcode})

                           (and http-utils/*http-messages* (#{:done :error :time-out} status))
                           (assoc :http-messages (-> data :http-messages))

                           (#{:error :time-out} status)
                           (assoc :phase (-> data :errors :phase)
                                  :message (-> data :errors :message)))))

    (when (and callback-url (final-status? status))
      (logging/with-mdc
        {:token                  (:token job)
         :url                    callback-url
         :institution-schac-home (:institution-schac-home job)}
        (do-async-callback config job)))))

(defn errors?
  "Return true if `x` has errors."
  [x]
  (and (map? x)
       (contains? x :errors)))

(defn retryable?
  "Return true if `x` has errors and can be retried."
  [x]
  (and (errors? x)
       (some-> x :errors :retryable? boolean)))

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
        config (update config :worker merge
                       {:queues        queues
                        :queue-fn      :institution-schac-home
                        :run-job-fn    (partial job/run! handlers (= (System/getenv "STORE_RIO_REQUESTS") "true"))
                        :set-status-fn (make-set-status-fn config)
                        :retryable-fn  retryable?
                        :error-fn      errors?})]
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
          (let [[type id] args]
            (println (resolver type id (:institution-oin client-info))))

          ("upsert" "delete" "delete-by-code")
          (let [[type id & remaining] args
                name-id (if (= "delete-by-code" command) ::rio/opleidingscode ::ooapi/id)
                job     (assoc client-info
                          name-id id
                          ::ooapi/type type
                          :action (if (= "delete-by-code" command) "delete" command)
                          :args remaining)
                result  (job/run! handlers job (= (System/getenv "STORE_RIO_REQUESTS") "true"))]
            (if (errors? result)
              (binding [*out* *err*]
                (prn result))
              (-> result json/write-str println))))))))
