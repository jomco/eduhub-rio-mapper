(ns nl.surf.eduhub-rio-mapper.ooapi.program
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.Program :as-alias Program]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]))

(s/def ::Program/name ::ooapi/LanguageTypedStrings)

(s/def ::Program
  (s/keys :req-un [::Program/name]
          :opt-un []))
