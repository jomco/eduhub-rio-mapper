(ns nl.surf.eduhub-rio-mapper.ooapi
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as course]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as program]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]]))

(s/def ::type
  #{"course"
    "education-specification"
    "program"})

(s/def ::id
  ::common/uuid)

(s/def ::root-url
  (re-spec #"http(s?)://.*"))

(s/def ::education-specification
  ::education-specification/EducationSpecificationTopLevel)

(s/def ::program
  ::program/Program)

(s/def ::course
  ::course/Course)

(s/def ::entity
  (s/or :education-specification ::education-specification
        :course ::course
        :program ::program))


(s/fdef education-specification-id
  :args (s/cat :entity ::entity)
  :ret ::id)

(defn education-specification-id
  "Return the education specification id for the given ooapi entity.

  Takes an EducationSpecification or a Course or a Program"
  [entity]
  {:pre [(some? entity)]}
  (or (:educationSpecification entity)
      (:educationSpecificationId entity)))
