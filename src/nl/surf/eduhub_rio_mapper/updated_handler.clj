(ns nl.surf.eduhub-rio-mapper.updated-handler
  (:require [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]))

(defn wrap-resolver
  "Get the RIO opleidingscode for the given entity.

  Inserts the code in the request as ::rio/opleidingscode."
  [f resolver]
  (fn [{:keys [::ooapi/entity] :as request}]
    (f (assoc request
              ::rio/opleidingscode (:code (resolver (ooapi/education-specification-id entity)))))))

(def missing-rio-id-message
  "RIO kent momenteel geen opleidingsonderdeel met eigenOpleidingseenheidSleutel %s.
Deze wordt automatisch aangemaakt wanneer er een update komt voor een
education specification.")

(defn updated-handler
  "Returns a RIO call or errors."
  [{:keys [::ooapi/entity ::rio/opleidingscode ::ooapi/type
           ::ooapi/education-specification]}]
  (if (and (not= "education-specification" type)
           (not opleidingscode))
    ;; If we're not inserting a new education-specification we need a
    ;; rio code (from an earlier inserted education-specification).
    {:errors (format missing-rio-id-message (ooapi/education-specification-id entity))}
    (let [entity (cond-> entity
                   opleidingscode
                   (assoc :rioId opleidingscode))]
      (case type
        "education-specification"
        {:action "aanleveren_opleidingseenheid"
         :ooapi entity
         :rio-sexp (opl-eenh/education-specification->opleidingseenheid entity)}

        "course"
        {:action "aanleveren_aangebodenOpleiding"
         :ooapi entity
         :rio-sexp (aangeboden-opl/course->aangeboden-opleiding entity opleidingscode)}

        "program"
        {:action "aanleveren_aangebodenOpleiding"
         :ooapi entity
         :rio-sexp (aangeboden-opl/program->aangeboden-opleiding entity (:educationSpecificationType education-specification) opleidingscode)}))))
