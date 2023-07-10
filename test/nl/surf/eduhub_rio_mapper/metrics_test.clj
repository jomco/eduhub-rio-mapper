(ns nl.surf.eduhub-rio-mapper.metrics-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.metrics :as metrics]))

(deftest render-metrics
  (is (= (metrics/render-metrics {"google" 12 "meta" 32})
         "active_and_queued_job_count{schac_home=\"google\"} 12\nactive_and_queued_job_count{schac_home=\"meta\"} 32")))
