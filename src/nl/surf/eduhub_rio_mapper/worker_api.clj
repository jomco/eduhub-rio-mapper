(ns nl.surf.eduhub-rio-mapper.worker-api
  (:require [compojure.core :refer [GET]]
            [compojure.route :as route]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.health :as health]
            [nl.surf.eduhub-rio-mapper.logging :refer [wrap-logging]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]])
  (:import [org.eclipse.jetty.server HttpConnectionFactory]))

(def public-routes
  (-> (compojure.core/routes
        (GET "/health" []
             {:health true}))))

(defn wrap-health
  [app config]
  (fn with-health [req]
    (let [res (app req)]
      (if (:health res)
        (if (health/redis-up? config)
          (assoc res :status http-status/ok
                     :body "OK")
          (assoc res :status http-status/service-unavailable
                     :body "Service Unavailable"))
        res))))

(def routes
  (-> (compojure.core/routes
        public-routes
        (route/not-found nil))))

(defn make-app [config]
  (-> routes
      (wrap-health config)
      (wrap-json-response)
      (wrap-logging)
      (wrap-trace-context)
      (defaults/wrap-defaults defaults/api-defaults)))

(defn serve-api
  [{{:keys [^Integer port host]} :worker-api-config :as config}]
  (jetty/run-jetty (make-app config)
                   {:host         host
                    :port         port
                    :join?        true
                    ;; Configure Jetty to not send server version
                    :configurator (fn [jetty]
                                    (doseq [connector (.getConnectors jetty)]
                                      (doseq [connFact (.getConnectionFactories connector)]
                                        (when (instance? HttpConnectionFactory connFact)
                                          (.setSendServerVersion (.getHttpConfiguration connFact) false)))))}))
