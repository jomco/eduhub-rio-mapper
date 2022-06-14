;; DO NOT EDIT - CHANGES WILL BE OVERWRITTEN
;; This file is generated automatically via lein generate-enums $PATH

(ns nl.surf.eduhub-rio-mapper.enums)

(def codeTypes
  "Enum gebruikt in EducationSpecification voor primaryCode en otherCodes."
  #{"brin" "crohoCreboCode" "programCode" "componentCode" "offeringCode" "organizationId" "buildingId" "bagId" "roomCode" "systemId" "productId" "nationalIdentityNumber" "studentNumber" "studielinkNumber" "esi" "userName" "accountId" "emailAdress" "groupCode" "isbn" "issn" "orcId" "uuid" "schacHome" "identifier"})

(def educationSpecificationTypes
  "Enum gebruikt in EducationSpecification voor educationSpecificationType."
  #{"program" "privateProgram" "cluster" "course"})

(def formalDocumentTypes
  "Enum gebruikt in EducationSpecification voor formalDocument."
  #{"diploma" "certificate" "no official document" "testimonial" "school advice"})

(def levels
  "Enum gebruikt in EducationSpecification voor level."
  #{"secondary vocational education" "secondary vocational education 1" "secondary vocational education 2" "secondary vocational education 3" "secondary vocational education 4" "associate degree" "bachelor" "master" "doctoral" "undefined" "undivided" "nt2-1" "nt2-2"})

(def sectors
  "Enum gebruikt in EducationSpecification voor sector."
  #{"secondary vocational education" "higher professional education" "university education"})

(def studyLoadUnits
  "Enum gebruikt in EducationSpecification voor studyLoad."
  #{"contacttime" "ects" "sbu" "sp" "hour"})

