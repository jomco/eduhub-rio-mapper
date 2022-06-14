(ns nl.surf.eduhub-rio-mapper.ooapi
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.EducationSpecification :as-alias EducationSpecification]
            [clojure.string :as string])
  (:import (java.time.format DateTimeFormatter DateTimeParseException)
           (java.time LocalDate)
           (java.util UUID)))

;; Enums
(def predefined-codeTypes
  "enum gebruikt in EducationSpecification voor primaryCode en otherCodes"
  #{"brin" "crohoCreboCode" "programCode" "componentCode" "offeringCode" "orgId" "buildingId" "bagId" "roomCode"
    "systemId" "productId" "nationalIdentityNumber" "studentNumber" "esi" "userName" "accountId" "emailAdress"
    "groupCode" "isbn" "issn" "orcId" "uuid" "schacHome" "identifier"})

(def education-specification-types
  "enum gebruikt in EducationSpecification voor educationSpecificationType"
  #{"program" "cluster" "course" "privateProgram"})

(def formal-document-types
  "enum gebruikt in EducationSpecification voor formalDocumentx"
  #{"diploma" "certificate" "no official document" "testimonial" "school advice"})

(def education-levels
  "enum gebruikt in EducationSpecification voor level"
  #{"secondary vocational education" "secondary vocational education 1" "secondary vocational education 2"
    "secondary vocational education 3" "secondary vocational education 4" "associate degree" "bachelor" "master"
    "doctoral" "undefined" "undivided" "nt2-1" "nt2-2"})

(def education-sectors
  "enum gebruikt in EducationSpecification voor sector"
  #{"secondary vocational education" "higher professional education" "university education"})

(def study-units
  "enum gebruikt in EducationSpecification voor studyLoad"
  #{"contacttime" "ects" "sbu" "sp" "hour"})

;; Patterns
(def language-code-pattern #"^[a-z]{2,4}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$")
(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

;; Validators
(defn valid-date? [date]
  (and (string? date)
       (try (LocalDate/parse date date-format)
            true
            (catch DateTimeParseException _ false))))

(defn valid-uuid? [uuid]
  (and (string? uuid)
       (try (UUID/fromString uuid)
            true
            (catch IllegalArgumentException _ false))))


;; Common types

(s/def :language/language
  (s/and string?
         #(re-matches language-code-pattern %)))

(s/def :language/value string?)

(s/def ::EducationSpecification/languageTypedStrings
  (s/coll-of
    (s/keys :req-un [:language/language
                     :language/value])))

(defn valid-codeType?
  "codeType should be in a predefined set or start with x-"
  [codeType]
  (or (contains? predefined-codeTypes codeType)
      (string/starts-with? codeType "x-")))

(s/def ::EducationSpecification/codeType valid-codeType?)
(s/def ::EducationSpecification/code string?)

(s/def ::EducationSpecification/codeTuple
  (s/keys :req-un [::EducationSpecification/codeType ::EducationSpecification/code]))

;; Top level response keys
(s/def ::EducationSpecification/abbreviation (s/and string? #(< (count %) 256)))
(s/def ::EducationSpecification/description ::EducationSpecification/languageTypedStrings)
(s/def ::EducationSpecification/educationSpecificationId string?)
(s/def ::EducationSpecification/educationSpecification valid-uuid?)
(s/def ::EducationSpecification/educationSpecificationSubType #{"variant"})
(s/def ::EducationSpecification/educationSpecificationType education-specification-types)
(s/def ::EducationSpecification/fieldsOfStudy (s/and string? #(re-matches #"\d{1,4}" %)))
(s/def ::EducationSpecification/formalDocument formal-document-types)
(s/def ::EducationSpecification/learningOutcomes (s/coll-of ::EducationSpecification/languageTypedStrings))
(s/def ::EducationSpecification/level education-levels)
(s/def ::EducationSpecification/levelOfQualification #{"1" "2" "3" "4" "4+" "5" "6" "7" "8"})
(s/def ::EducationSpecification/name ::EducationSpecification/languageTypedStrings)
(s/def ::EducationSpecification/link (s/and string? #(< (count %) 2048)))
(s/def ::EducationSpecification/otherCodes (s/coll-of ::EducationSpecification/codeTuple))
(s/def ::EducationSpecification/primaryCode ::EducationSpecification/codeTuple)
(s/def ::EducationSpecification/sector education-sectors)
(s/def :studyLoad/value number?)
(s/def ::EducationSpecification/studyLoadUnit study-units)
(s/def ::EducationSpecification/studyLoad (s/keys :req-un [::EducationSpecification/studyLoadUnit :studyLoad/value]))
(s/def ::EducationSpecification/validFrom valid-date?)
(s/def ::EducationSpecification/validTo valid-date?)

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
                          ::EducationSpecification/description
                          ::EducationSpecification/educationSpecification
                          ::EducationSpecification/fieldsOfStudy
                          ::EducationSpecification/formalDocument
                          ::EducationSpecification/learningOutcomes
                          ::EducationSpecification/level
                          ::EducationSpecification/levelOfQualification
                          ::EducationSpecification/link
                          ::EducationSpecification/otherCodes
                          ::EducationSpecification/sector
                          ::EducationSpecification/studyLoad
                          ::EducationSpecification/educationSpecificationSubType])
         valid-type-and-subtype?))

(s/def ::EducationSpecificationTopLevel
  (s/and ::EducationSpecification
         (s/keys :req-un [::EducationSpecification/validFrom]
                 :opt-un [::EducationSpecification/validTo])))