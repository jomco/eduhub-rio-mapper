;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.ooapi.education-specification
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.EducationSpecification :as-alias EducationSpecification]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [text-spec]]))

;; Top level response keys
(s/def ::EducationSpecification/abbreviation (text-spec 1 255))
(s/def ::EducationSpecification/children (s/coll-of ::common/uuid))
(s/def ::EducationSpecification/description ::common/LanguageTypedStrings)
(s/def ::EducationSpecification/educationSpecificationId ::common/uuid)
(s/def ::EducationSpecification/educationSpecificationSubType #{"variant"})
(s/def ::EducationSpecification/educationSpecificationType
  enums/educationSpecificationTypes)

(s/def ::EducationSpecification/formalDocument enums/formalDocumentTypes)
(s/def ::EducationSpecification/name ::common/nlLanguageTypedStrings)
(s/def ::EducationSpecification/link (text-spec 1 2048))
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
  (s/and (s/keys :req-un
                 [::EducationSpecification/educationSpecificationType
                  ::EducationSpecification/name
                  ::EducationSpecification/educationSpecificationId
                  ::EducationSpecification/primaryCode]
                 :opt-un
                 [::EducationSpecification/abbreviation
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
         valid-type-and-subtype?
         common/level-sector-map-to-rio?))

(s/def ::EducationSpecificationTopLevel
  (s/merge ::EducationSpecification
         (s/keys :req-un [::EducationSpecification/validFrom]
                 :opt-un [::EducationSpecification/validTo])))
