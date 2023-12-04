(ns nl.surf.eduhub-rio-mapper.metrics-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.metrics :as metrics]))

(deftest render-metrics
  (is (= (metrics/prometheus-render-metrics {"delft" 12 "leiden" 32}
                                            {"done" {"google" 123, "meta" 321}})
         (str "rio_mapper_jobs_total{schac_home=\"meta\", job_status=\"done\"} 321\n"
              "rio_mapper_jobs_total{schac_home=\"google\", job_status=\"done\"} 123\n"
              "rio_mapper_active_and_queued_job_count{schac_home=\"delft\"} 12\n"
              "rio_mapper_active_and_queued_job_count{schac_home=\"leiden\"} 32"))))
