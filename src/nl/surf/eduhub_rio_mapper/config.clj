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

(ns nl.surf.eduhub-rio-mapper.config
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.commands.processing :as processing]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore]))

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
                                        :default nil
                                        :in [:gateway-credentials :password]]
   :gateway-password-file              ["OOAPI Gateway Password File" :str
                                        :default nil
                                        :in [:gateway-credentials :password-file]]
   :gateway-root-url                   ["OOAPI Gateway Root URL" :http]
   :keystore                           ["Path to keystore" :file]
   :keystore-password                  ["Keystore password" :str
                                        :default nil
                                        :in [:keystore-pass]] ; name compatibility with clj-http
   :keystore-password-file             ["Keystore password file" :str
                                        :default nil
                                        :in [:keystore-pass-file]]
   :keystore-alias                     ["Key alias in keystore" :str]
   :truststore                         ["Path to trust-store" :file
                                        :in [:trust-store]] ; name compatibility with clj-http
   :truststore-password                ["Trust-store password" :str
                                        :default nil
                                        :in [:trust-store-pass]] ; name compatibility with clj-http
   :truststore-password-file           ["Trust-store password file" :str
                                        :default nil
                                        :in [:trust-store-pass-file]]
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
                                        :default nil
                                        :in [:auth-config :client-secret]]
   :surf-conext-client-secret-file     ["SurfCONEXT client secret for Mapper service file" :str
                                        :default nil
                                        :in [:auth-config :client-secret-file]]
   :api-port                           ["HTTP port for serving web API" :int
                                        :default 8080
                                        :in [:api-config :port]]
   :api-hostname                       ["Hostname for listing web API" :str
                                        :default "localhost"
                                        :in [:api-config :host]]
   :worker-api-port                    ["HTTP port for serving web API" :int
                                        :default 8080
                                        :in [:worker-api-config :port]]
   :worker-api-hostname                ["Hostname for listing web API" :str
                                        :default "localhost"
                                        :in [:worker-api-config :host]]
   :job-retry-wait-ms                  ["Number of ms to wait before retrying job" :int
                                        :default 5000
                                        :in [:worker :retry-wait-ms]]
   :job-max-retries                    ["Max number of retries of a job" :int
                                        :default 3
                                        :in [:worker :max-retries]]
   :redis-uri                          ["URI to redis" :str
                                        :default "redis://localhost"
                                        :in [:redis-conn :spec :uri]]
   :redis-uri-file                     ["URI to redis file" :str
                                        :default nil
                                        :in [:redis-conn :spec :uri-file]]
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

(defn help []
  (envopts/specs-description opts-spec))

(defmethod envopts/parse :file
  [s _]
  (let [f (io/file s)]
    (if (.isFile f)
      [f]
      [nil (str "not a file: `" s "`")])))

(defn dissoc-in
  "Return nested map with path removed."
  [m ks]
  (let [path (butlast ks)
        node (last ks)]
    (if (empty? path)
      (dissoc m node)
      (update-in m path dissoc node))))

(defn- load-secret-from-file [config k]
  (let [file-key-node (keyword (str (name (last k)) "-file"))
        root-key-path (pop k)
        file-key-path (conj root-key-path file-key-node)
        path (get-in config file-key-path)                  ; File path to secret
        config (dissoc-in config file-key-path)]            ; Remove -file key from config
    (if (nil? path)
      config
      (if (.exists (io/file path))
        (assoc-in config k (str/trim (slurp path)))         ; Overwrite config with secret from file
        (throw (ex-info (str "ENV var contains filename that does not exist: " path) {:filename path, :env-path k}))))))

(def key-value-pairs-with-optional-secret-files
  {:gateway-password [:gateway-credentials :password]
   :keystore-password [:keystore-pass]
   :redis-uri [:redis-conn :spec :uri]
   :surf-conext-client-secret [:auth-config :client-secret]
   :truststore-password [:trust-store-pass]})

(defn- validate-required-secrets [config]
  (let [missing-env (reduce
                      (fn [m [k v]] (if (get-in config v)
                                      m
                                      (assoc m k "missing")))
                      {}
                      key-value-pairs-with-optional-secret-files)]
    (when (not-empty missing-env)
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description missing-env))
      (System/exit 1))
    config))

(defn make-config
  []
  {:post [(some? (-> % :rio-config :credentials :certificate))]}
  (let [[config errs] (envopts/opts env opts-spec)]

    (when errs
      (.println *err* "Configuration error")
      (.println *err* (envopts/errs-description errs))
      (System/exit 1))
    (let [{:keys [clients-info-config
                  keystore
                  keystore-pass
                  keystore-alias
                  trust-store
                  trust-store-pass] :as cfg}
          (reduce load-secret-from-file config (vals key-value-pairs-with-optional-secret-files))]
      (-> cfg
          (validate-required-secrets)
          (assoc-in [:rio-config :credentials]
                    (keystore/credentials keystore
                                          keystore-pass
                                          keystore-alias
                                          trust-store
                                          trust-store-pass))
          (assoc :clients (clients-info/read-clients-data clients-info-config))))))

(defn make-config-and-handlers []
  (let [{:keys [clients] :as cfg} (make-config)
        handlers (processing/make-handlers cfg)
        config (update cfg :worker merge
                       {:queues        (clients-info/institution-schac-homes clients)
                        :queue-fn      :institution-schac-home
                        :run-job-fn    #(job/run! handlers % (= (System/getenv "STORE_HTTP_REQUESTS") "true"))
                        :set-status-fn (status/make-set-status-fn cfg)
                        :retryable-fn  status/retryable?
                        :error-fn      status/errors?})]
    {:handlers handlers :config config}))
