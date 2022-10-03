(ns nl.surf.eduhub-rio-mapper.api-server
  (:require [nl.surf.eduhub-rio-mapper.api :as api]
            [ring.adapter.jetty :as jetty]))

(defn serve-api
  [handlers {:keys [port host]}]
  (jetty/run-jetty (api/make-app handlers)
                   {:host host :port port :join? true}))
