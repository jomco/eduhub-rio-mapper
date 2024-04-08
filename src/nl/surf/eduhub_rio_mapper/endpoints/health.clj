(ns nl.surf.eduhub-rio-mapper.endpoints.health
  (:require [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

;; Should always return true if redis is up
(defn redis-up? [config]
  (try
    (worker/occupied-queues config)
    (catch Exception _ex
      false)))

(defn wrap-health
  [app config]
  (fn with-health [req]
    (let [res (app req)]
      (if (:health res)
        (if (redis-up? config)
          (assoc res :status http-status/ok
                     :body "OK")
          (assoc res :status http-status/service-unavailable
                     :body "Service Unavailable"))
        res))))
