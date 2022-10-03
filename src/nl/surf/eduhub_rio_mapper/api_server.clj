(ns nl.surf.eduhub-rio-mapper.api-server
  (:require [nl.surf.eduhub-rio-mapper.api :as api]
            [ring.adapter.jetty :as jetty]))

(defn serve-api
  [{{:keys [port host]} :api-config :as config}]
  (jetty/run-jetty (api/make-app config)
                   {:host host :port port :join? true}))
