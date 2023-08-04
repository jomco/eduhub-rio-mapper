(ns nl.surf.eduhub-rio-mapper.metrics-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.metrics :as metrics]))

(deftest render-metrics
  (is (= (metrics/render-metrics {"google" 12 "meta" 32})
         "rio_mapper_active_and_queued_job_count{schac_home=\"google\"} 12\nrio_mapper_active_and_queued_job_count{schac_home=\"meta\"} 32")))
