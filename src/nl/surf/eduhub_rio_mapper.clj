(ns nl.surf.eduhub-rio-mapper
  (:require [nl.surf.eduhub-rio-mapper.rio :as-alias rio]))

(def translate-educationSpecificationType
  {"program"        ::rio/HoOpleiding
   "cluster"        ::rio/HoOnderwijsEenhedenCluster
   "course"         ::rio/HoOnderWijsEenheid
   "privateProgram" ::rio/ParticuliereOpleiding})

(defn translate-EducationSpecification
  [{:keys [educationSpecificationType] :as root}]
  {::rio/type (translate-educationSpecificationType educationSpecificationType)})
