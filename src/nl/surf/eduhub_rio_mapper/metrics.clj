(ns nl.surf.eduhub-rio-mapper.metrics
  (:require [clojure.string :as str]))

(defn render-metrics [queue-count]
  {:pre [(map? queue-count)
         (every? string? (keys queue-count))
         (every? integer? (vals queue-count))]}
  (str/join "\n" (map (fn [[k v]] (format "rio_mapper_active_and_queued_job_count{schac_home=\"%s\"} %s" k v))
                      queue-count)))

(defn count-queues [grouped-queue-counter client-schac-homes]
  {:post [(map? %)
          (every? string? (keys %))
          (every? integer? (vals %))]}
  ;; For all keys in client-schac-homes, add value 0 in map if not already in map
  (reduce (fn [m k] (assoc m k (get m k 0)))
          (merge-with +
                      (grouped-queue-counter :queue)
                      (grouped-queue-counter :busy-queue))
          client-schac-homes))
