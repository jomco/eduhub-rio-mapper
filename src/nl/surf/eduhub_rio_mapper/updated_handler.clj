(ns nl.surf.eduhub-rio-mapper.updated-handler
  (:require [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]))

;; We have full entities in the request for upserts and then we need to
;; also fetch the education-specification from the entity if it's a
;; coarse or program.
;;
;; For deletes we don't have a full entity in the request (since it's
;; been deleted) and we only need the education-specification id if
;; the root entity is an education-specification.

(defn- education-specification-id
  [{:keys [::ooapi/entity ::ooapi/type ::ooapi/id]}]
  (if (= type "education-specification")
    id
    (ooapi/education-specification-id entity)))

(defn wrap-resolver
  "Get the RIO opleidingscode for the given entity.

  Inserts the code in the request as ::rio/opleidingscode."
  [f resolver]
  (fn [{:keys [institution-oin ::ooapi/entity ::ooapi/type] :as request}]
    {:pre [(or (= "education-specification" type)
               (some? entity))]}
    (f (assoc request
              ::rio/opleidingscode (-> request
                                       education-specification-id
                                       (resolver institution-oin)
                                       :code)))))

(def missing-rio-id-message
  "RIO kent momenteel geen opleidingsonderdeel met eigenOpleidingseenheidSleutel %s.
Deze wordt automatisch aangemaakt wanneer er een update komt voor een
education specification.")

(defn updated-handler
  "Returns a RIO call or errors."
  [{:keys [::ooapi/entity ::rio/opleidingscode ::ooapi/type
           ::ooapi/education-specification
           institution-oin]}]
  (assert institution-oin)
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
         :sender-oin institution-oin
         :rio-sexp (opl-eenh/education-specification->opleidingseenheid entity)}

        "course"
        {:action "aanleveren_aangebodenOpleiding"
         :ooapi entity
         :sender-oin institution-oin
         :rio-sexp (aangeboden-opl/course->aangeboden-opleiding entity opleidingscode)}

        "program"
        {:action "aanleveren_aangebodenOpleiding"
         :ooapi entity
         :sender-oin institution-oin
         :rio-sexp (aangeboden-opl/program->aangeboden-opleiding entity (:educationSpecificationType education-specification) opleidingscode)}))))

(defn deleted-handler-with-resolver
  "Get the RIO opleidingscode for the given entity.

  Inserts the code in the request as ::rio/opleidingscode."
  [resolver]
  (fn [{:keys [institution-oin ::ooapi/entity ::ooapi/type] :as request}]
    {:pre [institution-oin]}
    (let [{:keys [::ooapi/type ::ooapi/id institution-oin]} request]
      (case type
        "education-specification"
        (let [opleidingscode (-> request
                                 education-specification-id
                                 (resolver institution-oin)
                                 :code)]
          (if opleidingscode
            {:action     "verwijderen_opleidingseenheid"
             :sender-oin institution-oin
             :rio-sexp   [:duo:opleidingseenheidcode opleidingscode]}
            {:errors "Geen opleidingseenheid bekend voor opgegeven education-specification"
             :id     id
             :type   type}))

        ("course" "program")
        {:action "verwijderen_aangebodenOpleiding"
         :sender-oin institution-oin
         :rio-sexp [:duo:aangebodenOpleidingCode id]}))))
