(ns nl.surf.eduhub-rio-mapper.metrics
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

;; Retrieves the total number of processed jobs by status (started,done,error,time_out)
;; The job count is grouped per schachome. Returns a map with as keys the status (keyword)
;; and as values vectors of vectors with schachome (string) and count (integer).
(defn fetch-jobs-by-status-count [config]
  (let [process-pair
        (fn [[k cnt]]
          (let [[schach-home status] (clojure.string/split k #"/")]
            [status schach-home cnt]))
        process-triplet
        (fn [h [status schach-home cnt]]
          (assoc-in h [status schach-home] cnt))]
    (->> (worker/fetch-hash config worker/jobs-count-by-status-key-name)
         (partition 2)
         (map process-pair)
         (reduce process-triplet {}))))

(defn prometheus-current-jobs [queue-count]
  (map (fn [[k v]] (format "rio_mapper_active_and_queued_job_count{schac_home=\"%s\"} %s" k v))
       queue-count))

(defn prometheus-jobs-by-status [jobs-count-by-status]
  (mapcat (fn [status]
            (map (fn [[k v]]
                   (format "rio_mapper_%s_jobs_count{schac_home=\"%s\"} %s" (name status) k v))
                 (status jobs-count-by-status)))
          [:started :done :time_out :error]))

(defn prometheus-render-metrics [current-queue-count jobs-count-by-status]
  {:pre [(map? current-queue-count)
         (every? string? (keys current-queue-count))
         (every? integer? (vals current-queue-count))
         (map? jobs-count-by-status)
         (every? keyword? (keys jobs-count-by-status))
         (every? vector? (vals jobs-count-by-status))]}
  (str/join "\n" (into (prometheus-current-jobs current-queue-count)
                       (prometheus-jobs-by-status jobs-count-by-status))))

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
