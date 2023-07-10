(ns nl.surf.eduhub-rio-mapper.metrics
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.redis :as redis]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(defn render-metrics [queue-count]
  {:pre [(every? string? (keys queue-count))
         (every? integer? (vals queue-count))]}
  (str/join "\n" (map (fn [[k v]] (format "active_and_queued_job_count{schac_home=\"%s\"} %s" k v))
                      queue-count)))

(defn- count-by-key [query redis-conn]
  (let [prefix-len (dec (count query))]
    (->> (redis/keys redis-conn query)
         (map (juxt #(subs % prefix-len)
                    #(redis/llen redis-conn %)))
         (into {}))))

(defn count-queues [{:keys [redis-conn] :as config}]
  {:post [(map? %)
          (every? string? (keys %))
          (every? integer? (vals %))]}
  (merge-with +
              (count-by-key (worker/queue-key config "*") redis-conn)
              (count-by-key (worker/busy-queue-key config "*") redis-conn)))
