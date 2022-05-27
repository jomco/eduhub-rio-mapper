(ns nl.surf.eduhub-rio-mapper.ooapi
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.EducationSpecification :as-alias EducationSpecification]))

(s/def ::EducationSpecification/educationSpecificationType #{"program" "cluster" "course" "privateProgram"})
(s/def ::EducationSpecification/validFrom string?)
(s/def ::EducationSpecification/validTo string?)
(s/def ::EducationSpecification/educationSpecificationId string?)
(s/def ::EducationSpecification/educationSpecificationSubType #{"variant"})

(defn valid-type-and-subtype?
  "EducationSpecification should only have subType if type is 'program'."
  [{:keys [educationSpecificationType
           educationSpecificationSubType]
    :as education-specification}]
  (or (and (= educationSpecificationType "program")
           (= educationSpecificationSubType "variant"))
      (not (contains? education-specification :educationSpecificationSubType))))

(s/def ::EducationSpecification
  (s/and (s/keys :req-un [::EducationSpecification/educationSpecificationType
                          ;; is required because RIO requires `beginDatum`
                          ::EducationSpecification/validFrom
                          ::EducationSpecification/educationSpecificationId]
                 :opt-un [::EducationSpecification/validTo
                          ::EducationSpecification/educationSpecificationSubType])
         valid-type-and-subtype?))
