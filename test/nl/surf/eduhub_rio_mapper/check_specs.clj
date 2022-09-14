(ns nl.surf.eduhub-rio-mapper.check-specs
  "This runs clojure.spec.test for any fdef loaded."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as spec.test]
            [nl.surf.eduhub-rio-mapper.errors :refer [result-> result? errors?]]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.endpoints :as endpoints]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [expound.alpha :as expound]))


;; We define few custom functions to check here, because the actual
;; main code has some side-effects and we only want to test the
;; conversion of OOAPI data to RIO XML.
;;
;; Later refactoring may make these custom functions unnessecary, but
;; note that we only check the happy-path here; valid OOAPI data ->
;; valid RIO data.
(defn prep-body
  [{:keys [action rio-sexp]}]
  (soap/request-body action [rio-sexp] soap/beheren))

(s/fdef check-education-specification-handled
  :args (s/cat :spec ::education-specification/EducationSpecificationTopLevel
               :opleidingscode ::rio/OpleidingsEeenheidID-v01)
  :ret result?)

(defn check-education-specification-handled
  [education-specification opleidingscode]
  (let [r (result-> (endpoints/updated-handler {::ooapi/entity education-specification
                                                ::ooapi/type "education-specification"
                                                ::ooapi/id (:educationSpecificationId education-specification)
                                                ::rio/opleidingscode opleidingscode
                                                ::ooapi/education-specification education-specification})
                    (prep-body)
                    (soap/check-valid-xsd soap/beheren))]
    (if (errors? r)
      ;; ensure offending education spec is returned when spec check
      ;; fails on XSD errors
      (assoc r :ooapi education-specification)
      r)))

(defn main []
  (set! s/*explain-out* expound/printer)
  (let [syms (filter (fn [sym]
                       (re-matches #"nl\.surf\.eduhub-rio-mapper.*" (str sym)))
                     (spec.test/checkable-syms))
        _ (println "Running" (count syms) "checks:" syms)
        res (spec.test/check syms)]
#_    (prn res)
    (let [{:keys [total check-passed] :as summary} (spec.test/summarize-results res)]
#_      (prn summary)
      (expound/explain-results res)
      (=  total check-passed (count syms)))))

(defn -main
  [& _]
  (let [ok? (main)]
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
