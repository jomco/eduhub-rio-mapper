(ns nl.surf.eduhub-rio-mapper.ooapi.course
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.Course :as-alias Course]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]))

(s/def ::Course/name ::ooapi/LanguageTypedStrings)

(s/def ::Course
  (s/keys :req-un [::Course/name]
          :opt-un []))
