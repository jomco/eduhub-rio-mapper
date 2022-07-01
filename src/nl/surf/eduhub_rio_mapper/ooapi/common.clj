(ns nl.surf.eduhub-rio-mapper.ooapi.common
  "Common specs for use in the ooapi namespaces."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.ooapi.LanguageTypedString :as-alias LanguageTypedString]
            [nl.surf.eduhub-rio-mapper.ooapi.StudyLoadDescriptor :as-alias StudyLoadDescriptor]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]])
  (:import (java.time.format DateTimeFormatter DateTimeParseException)
           (java.time LocalDate)
           (java.util UUID)))

(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn get-localized-value
  "Get the first value of a LanguageTypedString where the language code matches the locale."
  [attr locales]
  (->> locales
       (keep (fn [locale] (some #(when (string/starts-with? (% :language) locale) (% :value))
                                attr)))
       first))

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

(s/def ::codeType
  (s/or :predefined enums/codeTypes
        :custom (re-spec #"x-[\w.]+")))
(s/def ::code string?)
(s/def ::codeTuple
  (s/keys :req-un [::codeType ::code]))
(s/def ::otherCodes (s/coll-of ::codeTuple))

(s/def ::StudyLoadDescriptor/value number?)
(s/def ::StudyLoadDescriptor/studyLoadUnit enums/studyLoadUnits)
(s/def ::studyLoad (s/keys :req-un [::StudyLoadDescriptor/studyLoadUnit ::StudyLoadDescriptor/value]))

(s/def ::fieldsOfStudy (re-spec #"\d{1,4}"))
(s/def ::learningOutcomes (s/coll-of ::LanguageTypedStrings))
(s/def ::level enums/levels)
(s/def ::levelOfQualification #{"1" "2" "3" "4" "4+" "5" "6" "7" "8"})
(s/def ::sector enums/sectors)

;; Address
(s/def ::additional ::LanguageTypedStrings)
(s/def ::addressType #{"postal" "visit" "deliveries" "billing" "teaching"})
(s/def ::city string?)
(s/def ::countryCode string?)
(s/def ::geolocation (s/keys :req-un [::latitude ::longitude]))
(s/def ::latitude number?)
(s/def ::longitude number?)
(s/def ::postalCode string?)
(s/def ::street string?)
(s/def ::streetNumber string?)
(s/def ::address (s/keys :req-un [::addressType]
                         :opt-un [::additional
                                  ::city
                                  ::countryCode
                                  ::geolocation
                                  ::postalCode
                                  ::street
                                  ::streetNumber]))
(s/def ::addresses (s/coll-of ::address))
