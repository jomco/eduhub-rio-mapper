(ns nl.surf.eduhub-rio-mapper.specs.ooapi
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.specs.course :as course]
            [nl.surf.eduhub-rio-mapper.specs.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.specs.program :as program]))

(s/def ::education-specification
  ::education-specification/EducationSpecificationTopLevel)

(s/def ::program
  ::program/program)

(s/def ::course
  ::course/course)

(s/def ::entity
  (s/or :education-specification ::education-specification
        :course ::course
        :program ::program))
