(ns nl.surf.eduhub-rio-mapper.api-server
  (:require [nl.surf.eduhub-rio-mapper.ring-handler :as ring-handler]
            [ring.adapter.jetty :as jetty]))

(defn serve-api
  [handlers {:keys [port host]}]
  (jetty/run-jetty (ring-handler/make-app handlers)
                   {:host host :port port :join? true}))
