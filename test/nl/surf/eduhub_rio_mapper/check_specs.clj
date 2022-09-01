(ns nl.surf.eduhub-rio-mapper.check-specs
  "This runs clojure.spec.test for any fdef loaded."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as spec.test]
            [nl.surf.eduhub-rio-mapper.errors :refer [result-> result?]]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.endpoints :as endpoints]
            [nl.surf.eduhub-rio-mapper.soap :as soap]))


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

(s/fdef check-education-specification-updated
  :args (s/cat :spec ::education-specification/EducationSpecificationTopLevel
               :opleidingscode string?)
  :ret result?)

(defn check-education-specification-updated
  [education-specification opleidingscode]
  (result-> education-specification
            (endpoints/education-specification-updated* opleidingscode)
            (prep-body)
            (soap/check-valid-xsd soap/beheren)))

(defn main []
  (let [syms (spec.test/checkable-syms)
        _ (println "Running" (count syms) "checks:" syms)
        res (apply spec.test/check syms)]
    (prn res)
    (let [{:keys [total check-passed] :as summary} (spec.test/summarize-results res)]
      (prn summary)
      (=  total check-passed (count syms)))))

(defn -main
  [& _]
  (let [ok? (main)]
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
