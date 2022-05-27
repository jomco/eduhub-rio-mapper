(ns nl.surf.eduhub-rio-mapper
  (:require [nl.surf.eduhub-rio-mapper.rio :as-alias rio]
            [nl.surf.eduhub-rio-mapper.rio.OpleidingsEenheid :as-alias rio.OpleidingsEenheid]))

(def translate-educationSpecificationType
  {"program"        ::rio/HoOpleiding
   "cluster"        ::rio/HoOnderwijsEenhedenCluster
   "course"         ::rio/HoOnderWijsEenheid
   "privateProgram" ::rio/ParticuliereOpleiding})

(defn translate-EducationSpecification
  [{:keys [educationSpecificationType
           validFrom
           validTo
           educationSpecificationSubType]}]
  (cond->
      {::rio/type (translate-educationSpecificationType educationSpecificationType)
       ::rio.OpleidingsEenheid/beginDatum validFrom}
      validTo
      (assoc ::rio.OpleidingsEenheid/eindDatum validTo)

      (= educationSpecificationType "program")
      (assoc ::rio.OpleidingsEenheid/soort
             (if educationSpecificationSubType
               ;; we don't check the actual value; input must be valid already
               "VARIANT"
               "OPLEIDING"))))
