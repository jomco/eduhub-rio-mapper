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

(ns nl.surf.eduhub-rio-mapper.check-specs
  "This runs clojure.spec.test for any fdef loaded."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as spec.test]
            [expound.alpha :as expound]
            [nl.surf.eduhub-rio-mapper.errors :refer [errors? result-> result?]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification
             :as education-specification]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as upserter]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]))

;; We define few custom functions to check here, because the actual
;; main code has some side-effects and we only want to test the
;; conversion of OOAPI data to RIO XML.
;;
;; Later refactoring may make these custom functions unnessecary, but
;; note that we only check the happy-path here; valid OOAPI data ->
;; valid RIO data.
(defn prep-body
  [{:keys [action rio-sexp]}]
  (soap/request-body action [rio-sexp] upserter/schema "1234" "1234"))

(s/fdef check-education-specification-handled
  :args (s/cat :spec ::education-specification/EducationSpecificationTopLevel
               :opleidingscode ::rio/OpleidingsEeenheidID-v01)
  :ret result?)

(defn check-education-specification-handled
  [education-specification opleidingscode]
  (let [r (result-> (updated-handler/update-mutation {::ooapi/entity                  education-specification
                                                      ::ooapi/type                    "education-specification"
                                                      ::ooapi/id                      (:educationSpecificationId education-specification)
                                                      ::rio/opleidingscode            opleidingscode
                                                      ::ooapi/education-specification education-specification
                                                      :institution-oin                "00000001800866472000"})
                    (prep-body)
                    (soap/guard-valid-sexp upserter/validator))]
    (if (errors? r)
      ;; ensure offending education spec is returned when spec check
      ;; fails on XSD errors
      (assoc r :ooapi education-specification)
      r)))

(defn main []
  (set! s/*explain-out* expound/printer)
  (let [syms [`check-education-specification-handled]
        _ (println "Running" (count syms) "checks:" syms)
        res (spec.test/check syms)
        {:keys [total check-passed]} (spec.test/summarize-results res)]
    (expound/explain-results res)
    (=  total check-passed (count syms))))

(defn -main
  [& _]
  (let [ok? (main)]
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
