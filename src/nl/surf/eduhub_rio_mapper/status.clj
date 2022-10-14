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
   token status & [payload]]
  (redis/set redis-conn
             (status-key config token)
             (cond-> {:status status}
                     payload (assoc :payload payload))
             "EX" status-ttl-sec))

(defn get
  [{:keys [redis-conn] :as config} token]
  (redis/get redis-conn (status-key config token)))

(defn- transform-done
  [{:keys [status payload]}]
  (let [code (-> payload
                 :aanleveren_opleidingseenheid_response
                 :opleidingseenheidcode)]
    (cond-> {:status status}
      code (assoc :attributes {:opleidingeenheidcode code}))))

(defn- transform-error
  [{:keys [status payload]}]
  (into {:status status}
        (select-keys payload [:phase :message])))

(defn transform
  [{:keys [status] :as raw}]
  (case status
    :done (transform-done raw)
    (:error :timeout) (transform-error raw)
    {:status status}))

(defn purge!
  [{:keys [redis-conn] :as config}]
  (doseq [k (redis/keys redis-conn (status-key config "*"))]
    (redis/del redis-conn k)))
