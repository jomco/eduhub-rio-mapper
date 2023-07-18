(ns nl.surf.eduhub-rio-mapper.metrics
  (:require [clojure.string :as str]))

(defn render-metrics [queue-count]
  {:pre [(map? queue-count)
         (every? string? (keys queue-count))
         (every? integer? (vals queue-count))]}
  (str/join "\n" (map (fn [[k v]] (format "active_and_queued_job_count{schac_home=\"%s\"} %s" k v))
                      queue-count)))

(defn count-queues [grouped-queue-counter]
  {:post [(map? %)
          (every? string? (keys %))
          (every? integer? (vals %))]}
  (merge-with +
              (grouped-queue-counter :queue)
              (grouped-queue-counter :busy-queue)))
