(ns nl.surf.eduhub-rio-mapper.ooapi.education-specification
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.EducationSpecification :as-alias EducationSpecification]
            [nl.surf.eduhub-rio-mapper.ooapi.StudyLoadDescriptor :as-alias StudyLoadDescriptor]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]))

(s/def ::EducationSpecification/codeType
  (s/or :predefined enums/codeTypes
        :custom (re-spec #"x-[\w.]+")))
(s/def ::EducationSpecification/code string?)

(s/def ::EducationSpecification/codeTuple
  (s/keys :req-un [::EducationSpecification/codeType ::EducationSpecification/code]))

;; Top level response keys
(s/def ::EducationSpecification/abbreviation (s/and string? #(< (count %) 256)))
(s/def ::EducationSpecification/children (s/coll-of ::common/uuid))
(s/def ::EducationSpecification/description ::common/LanguageTypedStrings)
(s/def ::EducationSpecification/educationSpecificationId string?)
(s/def ::EducationSpecification/educationSpecificationSubType #{"variant"})
(s/def ::EducationSpecification/educationSpecificationType enums/educationSpecificationTypes)
(s/def ::EducationSpecification/fieldsOfStudy (re-spec #"\d{1,4}"))
(s/def ::EducationSpecification/formalDocument enums/formalDocumentTypes)
(s/def ::EducationSpecification/learningOutcomes (s/coll-of ::common/LanguageTypedStrings))
(s/def ::EducationSpecification/level enums/levels)
(s/def ::EducationSpecification/levelOfQualification #{"1" "2" "3" "4" "4+" "5" "6" "7" "8"})
(s/def ::EducationSpecification/name ::common/LanguageTypedStrings)
(s/def ::EducationSpecification/link (s/and string? #(< (count %) 2048)))
(s/def ::EducationSpecification/otherCodes (s/coll-of ::EducationSpecification/codeTuple))
(s/def ::EducationSpecification/parent ::common/uuid)
(s/def ::EducationSpecification/primaryCode ::EducationSpecification/codeTuple)
(s/def ::EducationSpecification/sector enums/sectors)
(s/def ::StudyLoadDescriptor/value number?)
(s/def ::StudyLoadDescriptor/studyLoadUnit enums/studyLoadUnits)
(s/def ::EducationSpecification/studyLoad (s/keys :req-un [::StudyLoadDescriptor/studyLoadUnit ::StudyLoadDescriptor/value]))
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
                          ;; is required because RIO requires `beginDatum`
                          ::EducationSpecification/educationSpecificationId
                          ::EducationSpecification/primaryCode
                          ]
                 :opt-un [::EducationSpecification/abbreviation
                          ::EducationSpecification/children
                          ::EducationSpecification/description
                          ::EducationSpecification/fieldsOfStudy
                          ::EducationSpecification/formalDocument
                          ::EducationSpecification/learningOutcomes
                          ::EducationSpecification/level
                          ::EducationSpecification/levelOfQualification
                          ::EducationSpecification/link
                          ::EducationSpecification/otherCodes
                          ::EducationSpecification/parent
                          ::EducationSpecification/sector
                          ::EducationSpecification/studyLoad
                          ::EducationSpecification/educationSpecificationSubType])
         valid-type-and-subtype?))

(s/def ::EducationSpecificationTopLevel
  (s/merge ::EducationSpecification
         (s/keys :req-un [::EducationSpecification/validFrom]
                 :opt-un [::EducationSpecification/validTo])))
