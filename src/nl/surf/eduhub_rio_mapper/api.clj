(ns nl.surf.eduhub-rio-mapper.api
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.api.authentication :as authentication]
            [nl.surf.eduhub-rio-mapper.clients-info :refer [wrap-client-info]]
            [nl.surf.eduhub-rio-mapper.logging :refer [wrap-logging]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]])
  (:import java.util.UUID))

(defn extract-resource [uri]
  (when uri
    (if (str/starts-with? uri "/job/")
      (-> uri (subs 5) (str/split #"/" 2) last)             ; Remove action
      (log/error (str "invalid uri: " uri)))))

(defn wrap-job-enqueuer
  [app enqueue-fn]
  (fn with-job-enqueuer [{:keys [callback] :as req}]
    (let [{:keys [job] :as res} (app req)]
      (if job
        (let [attrs {:token    (UUID/randomUUID)
                     :callback callback
                     :resource (extract-resource (:uri req))}]
          (enqueue-fn (into {} (filter val (merge job attrs))))
          (assoc res :body attrs))
        res))))

(defn wrap-callback-extractor [app]
  (fn callback-extractor [req]
    (let [callback (get-in req [:headers "x-callback"])]
      (app (if callback
             (assoc req :callback callback)
             req)))))

(defn wrap-status-getter
  [app config]
  (fn with-status-getter [req]
    (let [res (app req)]
      (if-let [token (:token res)]
        (if-let [status (status/get config token)]
          (assoc res
                 :status http-status/ok
                 :body (status/transform status))
          (assoc res
                 :status http-status/not-found
                 :body {:status :unknown}))
        res))))

(def types {"courses"                  "course"
            "education-specifications" "education-specification"
            "programs"                 "program"})

(def actions #{"upsert" "delete"})

(defroutes routes
  (POST "/job/:action/:type/:id"
        {{:keys [action type id]} :params :as request}
        (let [type   (types type)
              action (actions action)]
          (when (and type action)
            {:job (-> request
                      (select-keys [:institution-schac-home
                                    :institution-oin
                                    :trace-context])
                      (assoc :action      action
                             ::ooapi/type type
                             ::ooapi/id   id))})))

  (GET "/status/:token" [token]
       {:token token})

  (route/not-found nil))

(defn make-app [{:keys [auth-config clients] :as config}]
  (-> routes
      (wrap-job-enqueuer (partial worker/enqueue! config))
      (wrap-callback-extractor)
      (wrap-status-getter config)
      (wrap-client-info clients)
      (authentication/wrap-authentication (-> (authentication/make-token-authenticator auth-config)
                                              (authentication/cache-token-authenticator {:ttl-minutes 10})))
      (wrap-json-response)
      (wrap-logging)
      (wrap-trace-context)
      (defaults/wrap-defaults defaults/api-defaults)))

(defn serve-api
  [{{:keys [port host]} :api-config :as config}]
  (jetty/run-jetty (make-app config)
                   {:host host :port port :join? true}))
