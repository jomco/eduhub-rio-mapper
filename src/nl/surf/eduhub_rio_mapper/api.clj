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

(ns nl.surf.eduhub-rio-mapper.api
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [compojure.core :refer [GET POST]]
            [compojure.route :as route]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.api.authentication :as authentication]
            [nl.surf.eduhub-rio-mapper.clients-info :refer [wrap-client-info] :as clients-info]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.logging :refer [wrap-logging with-mdc]]
            [nl.surf.eduhub-rio-mapper.metrics :as metrics]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :as response])
  (:import java.util.UUID
           [java.net MalformedURLException URL]
           [org.eclipse.jetty.server HttpConnectionFactory]))

(defn wrap-job-enqueuer
  [app enqueue-fn]
  (fn with-job-enqueuer [req]
    (let [{:keys [job] :as res} (app req)]
      (if job
        (let [token (UUID/randomUUID)]
          (with-mdc {:token token}
            (enqueue-fn (assoc job :token token)))
          (assoc res :body {:token token}))
        res))))

(defn- valid-url? [url]
  (try
    (URL. url)
    (str/starts-with? url "http")                           ; Reject non-http protocols like file://
    (catch MalformedURLException _
      false)))

(defn wrap-callback-extractor [app]
  (fn callback-extractor [req]
    (let [callback-url          (get-in req [:headers "x-callback"])
          {:keys [job] :as res} (app req)]
      (if (or (nil? job)
              (nil? callback-url))
        res
        (if (valid-url? callback-url)
          (update res :job assoc ::job/callback-url callback-url)
          {:status http-status/bad-request :body "Malformed callback url"})))))

(defn wrap-metrics-getter
  [app fetch-jobs-by-status count-queues-fn]
  (fn with-metrics-getter [req]
    (let [res (app req)]
      (cond-> res
              (:metrics res)
              (assoc :status http-status/ok
                     :body (metrics/render-metrics (count-queues-fn) (fetch-jobs-by-status)))))))

(defn wrap-status-getter
  [app config]
  (fn with-status-getter [req]
    (let [res (app req)]
      (if-let [token (:token res)]
        (with-mdc {:token token}
          (if-let [status (status/get config token)]
            (assoc res
                   :status http-status/ok
                   :body (if (= "false" (get-in req [:params :http-messages] "false"))
                           (dissoc status :http-messages)
                           status))
            (assoc res
                   :status http-status/not-found
                   :body {:status :unknown})))
        res))))

(defn wrap-uuid-validator [app]
  (fn [req]
    (let [uuid (or (get-in req [:params :id])
                   (get-in req [:params :token]))]
      (if (or (nil? uuid)
              (common/valid-uuid? uuid))
        (app req)
        {:status http-status/bad-request :body "Invalid UUID"}))))

(defn wrap-code-validator [app]
  (fn [req]
    (let [res (app req)
          ao-code (get-in res [:job ::rio/aangeboden-opleiding-code])
          invalid-ao-code (and (some? ao-code) (not (common/valid-uuid? ao-code)))
          opl-code (get-in res [:job ::rio/opleidingscode])
          invalid-opleidingscode (and (some? opl-code) (not (s/valid? ::rio/OpleidingsEenheidID-v01 opl-code)))]
      (cond
        invalid-ao-code
        {:status http-status/bad-request :body (format "Invalid aangeboden opleidingcode '%s'" ao-code)}

        invalid-opleidingscode
        {:status http-status/bad-request :body (format "Invalid opleidingscode '%s'" opl-code)}

        :else
        res))))

(defn wrap-access-control-private [app]
  (fn [{:keys [institution-oin] :as req}]
    (if institution-oin
      (app req)
      (response/status http-status/forbidden))))

(defn wrap-access-control-read-only [app]
  (fn [{:keys [client-id] :as req}]
    (if client-id
      (app req)
      (response/status http-status/forbidden))))

(def types {"courses"                  "course"
            "education-specifications" "education-specification"
            "programs"                 "program"})

(def actions #{"upsert" "delete" "dry-run-upsert" "link"})

(defn fetch-jobs-by-status-count [config])

(defn job-route [{{:keys [action type id]} :params :as request}]
  (let [type   (types type)
        action (actions action)]
    (when (and type action)
      {:job (-> request
                (select-keys [:institution-schac-home
                              :institution-name
                              :institution-oin
                              :trace-context])
                (assoc :action      action
                       ::ooapi/type type
                       ::ooapi/id   id))})))

(defn link-route [{{:keys [rio-code type]} :params :as request}]
  {:pre [(types type)]}
  (let [result   (job-route (assoc-in request [:params :action] "link"))
        codename (if (= type "education-specifications") ::rio/opleidingscode ::rio/aangeboden-opleiding-code)]
    (when result
      (assoc-in result [:job codename] rio-code))))


(def private-routes
  (-> (compojure.core/routes
        ;; Unlink is link to `nil`
        (POST "/job/unlink/:rio-code/:type" request
          (link-route request))

        (POST "/job/:action/:type/:id" request
          (job-route request))

        (POST "/job/dry-run/upsert/:type/:id" request
          (job-route (assoc-in request [:params :action] "dry-run-upsert")))

        (POST "/job/link/:rio-code/:type/:id" request
          (link-route request)))

      (compojure.core/wrap-routes wrap-uuid-validator)
      (compojure.core/wrap-routes wrap-access-control-private)))

(def public-routes
  (GET "/metrics" []
    {:metrics true}))

(def read-only-routes
  (-> (GET "/status/:token" [token] {:token token})
      (compojure.core/wrap-routes wrap-uuid-validator)
      (compojure.core/wrap-routes wrap-access-control-read-only)))

(def routes
  (-> (compojure.core/routes
        private-routes
        public-routes
        read-only-routes
        (route/not-found nil))))

(defn make-app [{:keys [auth-config clients] :as config}]
  (let [institution-schac-homes (clients-info/institution-schac-homes clients)
        count-per-schac-home (zipmap institution-schac-homes (repeat 0))
        initial-value (zipmap [:started :error :time_out :done] (repeat count-per-schac-home))]
    (prn initial-value)
    (reset! jobs-by-status-count initial-value)
    (-> routes
        (wrap-uuid-validator)
        (wrap-code-validator)
        (wrap-callback-extractor)
        (wrap-job-enqueuer (partial worker/enqueue! config))
        (wrap-status-getter config)
        (wrap-metrics-getter (fn [] (fetch-jobs-by-status-count config))
                             (fn [] (metrics/count-queues #(worker/queue-counts-by-key % config) institution-schac-homes)))
        (wrap-client-info clients)
        (authentication/wrap-authentication (-> (authentication/make-token-authenticator auth-config)
                                                (authentication/cache-token-authenticator {:ttl-minutes 10})))
        (wrap-json-response)
        (wrap-logging)
        (wrap-trace-context)
        (defaults/wrap-defaults defaults/api-defaults))))

(defn serve-api
  [{{:keys [^Integer port host]} :api-config :as config}]
  (jetty/run-jetty (make-app config)
                   {:host host
                    :port port
                    :join? true
                    ;; Configure Jetty to not send server version
                    :configurator (fn [jetty]
                                    (doseq [connector (.getConnectors jetty)]
                                      (doseq [connFact (.getConnectionFactories connector)]
                                        (when (instance? HttpConnectionFactory connFact)
                                          (.setSendServerVersion (.getHttpConfiguration connFact) false)))))}))
