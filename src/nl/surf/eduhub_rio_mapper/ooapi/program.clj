(ns nl.surf.eduhub-rio-mapper.ooapi.program
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.Program :as-alias Program]))

(s/def ::Program/abbreviation string?)
(s/def ::Program/admissionRequirements ::common/LanguageTypedStrings)
(s/def ::Program/assessment ::common/LanguageTypedStrings)
(s/def ::Program/children (s/coll-of string?))
(s/def ::Program/consentParticipationSTAP string?)
(s/def ::Program/coordinators (s/coll-of string?))
(s/def ::Program/description ::common/LanguageTypedStrings)
(s/def ::Program/duration string?)                          ; TODO validate ISO 8601
(s/def ::Program/educationLocationCode string?)
(s/def ::Program/educationOffererCode string?)
(s/def ::Program/educationSpecification string?)
(s/def ::Program/enrollment ::common/LanguageTypedStrings)
(s/def ::Program/firstStartDate ::common/date)
(s/def ::Program/foreignPartner string?)
(s/def ::Program/foreignPartners (s/coll-of ::Program/foreignPartner))
(s/def ::Program/jointPartnerCode string?)
(s/def ::Program/jointPartnerCodes (s/coll-of ::Program/jointPartnerCode))
(s/def ::Program/link string?)
(s/def ::Program/modeOfStudy string?)                       ; TODO enum
(s/def ::Program/name ::common/LanguageTypedStrings)
(s/def ::Program/organization string?)
(s/def ::Program/parent string?)
(s/def ::Program/primaryCode ::common/codeTuple)
(s/def ::Program/programId ::common/uuid)
(s/def ::Program/programType string?)                       ; TOOO enum
(s/def ::Program/qualificationAwarded string?)
(s/def ::Program/qualificationRequirements ::common/LanguageTypedStrings)
(s/def ::Program/resources (s/coll-of string?))
(s/def ::Program/teachingLanguage string?)                  ; TODO format not validated
(s/def ::Program/validFrom ::common/date)
(s/def ::Program/validTo ::common/date)

(s/def ::Program/rio-consumer
  (s/keys :req-un [::Program/educationOffererCode]
          :opt-un [::Program/educationLocationCode
                   ::Program/consentParticipationSTAP
                   ::Program/foreignPartners
                   ::Program/jointPartnerCodes
                   ::common/level
                   ::common/sector
                   ::common/levelOfQualification]))

(s/def ::Program/consumerKey (s/and string? #(not= % "rio")))
(s/def ::Program/other-consumer (s/keys :req-un [::Program/consumerKey]))
(s/def ::Program/consumer (s/or :other ::Program/other-consumer :rio ::Program/rio-consumer))
(s/def ::Program/consumers (s/coll-of ::Program/consumer))

(s/def ::Program
  (s/keys :req-un [::Program/programId
                   ::Program/duration
                   ::Program/educationSpecification
                   ::Program/name
                   ::Program/validFrom]
          :opt-un [::Program/abbreviation
                   ::Program/admissionRequirements
                   ::Program/assessment
                   ::Program/children
                   ::Program/consumers
                   ::Program/coordinators
                   ::Program/description
                   ::Program/enrollment
                   ::Program/link
                   ::Program/modeOfStudy
                   ::Program/organization
                   ::Program/parent
                   ::Program/qualificationAwarded
                   ::Program/qualificationRequirements
                   ::Program/resources
                   ::Program/teachingLanguage
                   ::common/addresses
                   ::common/fieldsOfStudy
                   ::common/learningOutcomes
                   ::common/otherCodes
                   ::common/studyLoad]))
