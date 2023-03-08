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
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.api.authentication :as authentication]
            [nl.surf.eduhub-rio-mapper.clients-info :refer [wrap-client-info]]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.logging :refer [wrap-logging with-mdc]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [ring.adapter.jetty9 :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]])
  (:import java.util.UUID
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

(defn wrap-callback-extractor [app]
  (fn callback-extractor [req]
    (let [callback-url          (get-in req [:headers "x-callback"])
          {:keys [job] :as res} (app req)]
      (if (or (nil? job)
              (nil? callback-url))
        res
        (update res :job assoc ::job/callback-url callback-url)))))

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

(def types {"courses"                  "course"
            "education-specifications" "education-specification"
            "programs"                 "program"})

(def actions #{"upsert" "delete" "dry-run-upsert"})

(defroutes routes
  (POST "/job/:action/:type/:id"
        {{:keys [action type id]} :params :as request}
        (let [type   (types type)
              action (actions action)]
          (when (and type action)
            {:job (-> request
                      (select-keys [:institution-schac-home
                                    :institution-oin
                                    :onderwijsbestuurcode
                                    :trace-context])
                      (assoc :action      action
                             ::ooapi/type type
                             ::ooapi/id   id))})))

  (GET "/status/:token" [token]
       {:token token})

  (route/not-found nil))

(defn make-app [{:keys [auth-config clients] :as config}]
  (-> routes
      (wrap-callback-extractor)
      (wrap-job-enqueuer (partial worker/enqueue! config))
      (wrap-status-getter config)
      (wrap-client-info clients)
      (authentication/wrap-authentication (-> (authentication/make-token-authenticator auth-config)
                                              (authentication/cache-token-authenticator {:ttl-minutes 10})))
      (wrap-json-response)
      (wrap-logging)
      (wrap-trace-context)
      (defaults/wrap-defaults defaults/api-defaults)))

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
