(ns nl.surf.eduhub-rio-mapper.ooapi.common
  "Common specs for use in the ooapi namespaces."
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.LanguageTypedString :as-alias LanguageTypedString]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]])
  (:import (java.time.format DateTimeFormatter DateTimeParseException)
           (java.time LocalDate)
           (java.util UUID)))

(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn valid-date? [date]
  (and (string? date)
       (try (LocalDate/parse date date-format)
            true
            (catch DateTimeParseException _ false))))

(s/def ::date
  (s/and (re-spec #"\d\d\d\d-[01]\d-[012]\d")
         valid-date?))

(defn valid-uuid? [uuid]
  (try (UUID/fromString uuid)
       true
       (catch IllegalArgumentException _ false)))

(s/def ::uuid
  (s/and (re-spec #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
         valid-uuid?))

;; Common types

(s/def ::LanguageTypedString/language
  (re-spec #"^[a-z]{2,4}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$"))

(s/def ::LanguageTypedString/value string?)

(s/def ::LanguageTypedStrings
  (s/coll-of
    (s/keys :req-un [::LanguageTypedString/language
                     ::LanguageTypedString/value])))
