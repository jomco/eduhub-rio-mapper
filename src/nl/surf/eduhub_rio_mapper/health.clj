(ns nl.surf.eduhub-rio-mapper.health
  (:require [nl.surf.eduhub-rio-mapper.worker :as worker]))

;; Should always return true if redis is up
(defn redis-up? [config]
  (try
    (let [occ (worker/occupied-queues config)]
      (vector? occ))
    (catch Exception _ex
      false)))
