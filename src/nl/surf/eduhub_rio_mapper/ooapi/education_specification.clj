(ns nl.surf.eduhub-rio-mapper.ooapi.education-specification
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.EducationSpecification :as-alias EducationSpecification]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]))

;; Top level response keys
(s/def ::EducationSpecification/abbreviation (s/and string? #(< (count %) 256)))
(s/def ::EducationSpecification/children (s/coll-of ::common/uuid))
(s/def ::EducationSpecification/description ::common/LanguageTypedStrings)
(s/def ::EducationSpecification/educationSpecificationId string?)
(s/def ::EducationSpecification/educationSpecificationSubType #{"variant"})
(s/def ::EducationSpecification/educationSpecificationType enums/educationSpecificationTypes)
(s/def ::EducationSpecification/formalDocument enums/formalDocumentTypes)
(s/def ::EducationSpecification/name ::common/LanguageTypedStrings)
(s/def ::EducationSpecification/link (s/and string? #(< (count %) 2048)))
(s/def ::EducationSpecification/parent ::common/uuid)
(s/def ::EducationSpecification/primaryCode ::common/codeTuple)
(s/def ::EducationSpecification/validFrom ::common/date)
(s/def ::EducationSpecification/validTo ::common/date)

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
                          ::EducationSpecification/name
                          ::EducationSpecification/educationSpecificationId
                          ::EducationSpecification/primaryCode]
                 :opt-un [::EducationSpecification/abbreviation
                          ::EducationSpecification/children
                          ::EducationSpecification/description
                          ::EducationSpecification/educationSpecificationSubType
                          ::EducationSpecification/formalDocument
                          ::EducationSpecification/link
                          ::EducationSpecification/parent
                          ::common/fieldsOfStudy
                          ::common/learningOutcomes
                          ::common/level
                          ::common/levelOfQualification
                          ::common/otherCodes
                          ::common/sector
                          ::common/studyLoad])
         valid-type-and-subtype?))

(s/def ::EducationSpecificationTopLevel
  (s/merge ::EducationSpecification
         (s/keys :req-un [::EducationSpecification/validFrom]
                 :opt-un [::EducationSpecification/validTo])))
