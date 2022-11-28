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
  (or (:educationSpecification entity)
      (:educationSpecificationId entity)))
