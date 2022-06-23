(ns nl.surf.eduhub-rio-mapper.ooapi
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.LanguageTypedString :as-alias LanguageTypedString])
  (:import (java.time.format DateTimeFormatter DateTimeParseException)
           (java.time LocalDate)
           (java.util UUID)))

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

(s/def ::LanguageTypedString/language
  (s/and string?
         #(re-matches language-code-pattern %)))

(s/def ::LanguageTypedString/value string?)

(s/def ::LanguageTypedStrings
  (s/coll-of
    (s/keys :req-un [::LanguageTypedString/language
                     ::LanguageTypedString/value])))
