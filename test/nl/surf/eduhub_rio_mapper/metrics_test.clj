;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.metrics-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.endpoints.metrics :as metrics]))

(deftest render-metrics
  (is (= (str "rio_mapper_jobs_total{schac_home=\"meta\", institution_name=\"facebook\", job_status=\"done\"} 321\n"
              "rio_mapper_jobs_total{schac_home=\"google\", institution_name=\"alphabet\", job_status=\"done\"} 123\n"
              "rio_mapper_active_and_queued_job_count{schac_home=\"delft\", institution_name=\"TU\"} 12\n"
              "rio_mapper_active_and_queued_job_count{schac_home=\"leiden\", institution_name=\"LU\"} 32")
         (metrics/prometheus-render-metrics {"delft" 12 "leiden" 32}
                                            {"done" {"google" 123, "meta" 321}}
                                            {"delft" "TU", "leiden" "LU", "google" "alphabet", "meta" "facebook"}))))
