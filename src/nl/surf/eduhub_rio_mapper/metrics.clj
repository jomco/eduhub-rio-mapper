(ns nl.surf.eduhub-rio-mapper.metrics
  (:require [clojure.string :as str]))

(defn current-jobs [queue-count]
  (map (fn [[k v]] (format "rio_mapper_active_and_queued_job_count{schac_home=\"%s\"} %s" k v))
       queue-count))

(defn jobs-by-status [jobs-count-by-status]
  (mapcat (fn [status]
            (map (fn [[k v]] (format "rio_mapper_%s_jobs_count{schac_home=\"%s\"} %s" (name status) k v))
                 (status jobs-count-by-status)))
          [:started :done :time_out :error]))

(defn render-metrics [current-queue-count jobs-count-by-status]
  {:pre [(map? current-queue-count)
         (every? string? (keys current-queue-count))
         (every? integer? (vals current-queue-count))
         (map? jobs-count-by-status)]}
  (str/join "\n" (into (current-jobs current-queue-count)
                       (jobs-by-status jobs-count-by-status))))

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
