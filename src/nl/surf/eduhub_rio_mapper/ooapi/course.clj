(ns nl.surf.eduhub-rio-mapper.ooapi.course
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.Course :as-alias Course]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [text-spec]]))

(s/def ::Course/abbreviation string?)
(s/def ::Course/consentParticipationSTAP string?)
(s/def ::Course/courseId ::common/uuid)
(s/def ::Course/description ::common/LanguageTypedStrings)
(s/def ::Course/educationOffererCode string?)
(s/def ::Course/educationLocationCode string?)
(s/def ::Course/educationSpecification string?)
(s/def ::Course/firstStartDate ::common/date)
(s/def ::Course/foreignPartner string?)
(s/def ::Course/foreignPartners (s/coll-of ::Course/foreignPartner))
(s/def ::Course/jointPartnerCode string?)
(s/def ::Course/jointPartnerCodes (s/coll-of ::Course/jointPartnerCode))
(s/def ::Course/link string?)
(s/def ::Course/name ::common/LanguageTypedStrings)
(s/def ::Course/teachingLanguage string?)
(s/def ::Course/validFrom ::common/date)
(s/def ::Course/validTo ::common/date)

(s/def ::Course/rio-consumer (s/keys :req-un [::Course/educationOffererCode]
                                     :opt-un [::Course/educationLocationCode
                                              ::Course/consentParticipationSTAP
                                              ::Course/foreignPartners
                                              ::Course/jointPartnerCodes]))
(s/def ::Course/consumerKey (s/and string? #(not= % "rio")))
(s/def ::Course/other-consumer (s/keys :req-un [::Course/consumerKey]))
(s/def ::Course/consumer (s/or :other ::Course/other-consumer :rio ::Course/rio-consumer))
(s/def ::Course/consumers (s/coll-of ::Course/consumer))

(s/def ::Course
  (s/keys :req-un [::Course/consumers
                   ::Course/courseId
                   ::common/duration
                   ::Course/educationSpecification
                   ::Course/name
                   ::Course/validFrom]
          :opt-un [::Course/abbreviation
                   ::Course/description
                   ::Course/link
                   ::Course/teachingLanguage]))
