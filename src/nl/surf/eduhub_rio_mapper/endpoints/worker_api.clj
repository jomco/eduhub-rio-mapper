(ns nl.surf.eduhub-rio-mapper.endpoints.worker-api
  (:require [compojure.core :refer [GET]]
            [compojure.route :as route]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.endpoints.app-server :as app-server]
            [nl.surf.eduhub-rio-mapper.endpoints.health :as health]
            [nl.surf.eduhub-rio-mapper.utils.logging :refer [wrap-logging]]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]]))

(def public-routes
  (-> (compojure.core/routes
        (GET "/health" []
             {:health true}))))

(def routes
  (-> (compojure.core/routes
        public-routes
        (route/not-found nil))))

(defn make-app [config]
  (-> routes
      (health/wrap-health config)
      (wrap-json-response)
      (wrap-logging)
      (wrap-trace-context)
      (defaults/wrap-defaults defaults/api-defaults)))

(defn serve-api
  [{{:keys [^Integer port host]} :worker-api-config :as config}]
  (app-server/run-jetty (make-app config) host port))
