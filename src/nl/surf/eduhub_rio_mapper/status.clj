(ns nl.surf.eduhub-rio-mapper.status
  (:require [nl.surf.eduhub-rio-mapper.redis :as redis])
  (:refer-clojure :exclude [get]))

(defn- status-key
  [{:keys [redis-key-prefix]
    :or {redis-key-prefix "eduhub-rio-mapper"}}
   token]
  (str redis-key-prefix ".status:" token))

(defn set!
  [{:keys [redis-conn status-ttl-sec] :as config}
   token status & [data]]
  (redis/set redis-conn
             (status-key config token)
             (-> {}
                 (into data)
                 (assoc :status status))
             "EX" status-ttl-sec))

(defn get
  [{:keys [redis-conn] :as config} token]
  (redis/get redis-conn (status-key config token)))

(defn purge!
  [{:keys [redis-conn] :as config}]
  (doseq [k (redis/keys redis-conn (status-key config "*"))]
    (redis/del redis-conn k)))
