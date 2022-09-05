(ns nl.surf.eduhub-rio-mapper.ooapi.common
  "Common specs for use in the ooapi namespaces."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.ooapi.LanguageTypedString :as-alias LanguageTypedString]
            [nl.surf.eduhub-rio-mapper.ooapi.LanguageTypedStringEN :as-alias LanguageTypedStringEN]
            [nl.surf.eduhub-rio-mapper.ooapi.LanguageTypedStringNL :as-alias LanguageTypedStringNL]
            [nl.surf.eduhub-rio-mapper.ooapi.StudyLoadDescriptor :as-alias StudyLoadDescriptor]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec text-spec]]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter DateTimeParseException)
           (java.util UUID)))

(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn get-localized-value
  "Get the first value of a LanguageTypedString where the language code matches the locale. The provided locales are tried in order."
  [attr locales]
  (->> locales
       (keep (fn [locale] (some #(when (string/starts-with? (% :language) locale) (% :value))
                                attr)))
       first))

(defn valid-date? [date]
  (and (string? date)
       (try (let [d (LocalDate/parse date date-format)]
              ;; XSD schema does not accept "Year zero".
              (not (zero? (.getYear d))))
            (catch DateTimeParseException _ false))))

(s/def ::date
  (s/and (re-spec #"\d\d\d\d-[01]\d-[0123]\d")
         valid-date?))

(s/def ::duration
  (re-spec #"^P(\d+Y)?(\d+M)?(\d+W)?(\d+D)?(T(\d+H)?(\d+M)?(\d+S)?)?$"))

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

(s/def ::LanguageTypedString/value
  (text-spec 1 1000))

(s/def ::LanguageTypedString
  (s/keys :req-un [::LanguageTypedString/language
                   ::LanguageTypedString/value]))

(s/def ::LanguageTypedStringNL/language
  #{"nl-NL" "nl-BE"})

(s/def ::LanguageTypedStringEN/language
  #{"en-AU"
    "en-BZ"
    "en-CA"
    "en-CB"
    "en-GB"
    "en-IE"
    "en-JM"
    "en-NZ"
    "en-PH"
    "en-TT"
    "en-US"
    "en-ZA"
    "en-ZW"})

(s/def ::nlLanguageTypedString
  (s/keys :req-un [::LanguageTypedStringNL/language
                   ::LanguageTypedString/value]))

(s/def ::enLanguageTypedString
  (s/keys :req-un [::LanguageTypedStringEN/language
                   ::LanguageTypedString/value]))

;; A collection of language typed strings with any set of languages
(s/def ::LanguageTypedStrings
  (s/coll-of ::LanguageTypedString))

;; A collection of language typed strings with at least one dutch entry
(s/def ::nlLanguageTypedStrings
  (s/cat :head (s/* ::LanguageTypedString)
         :nl ::nlLanguageTypedString
         :tail (s/* ::LanguageTypedString)))

;; A collection of language typed strings with at least one english entry
(s/def ::enLanguageTypedStrings
  (s/cat :head (s/* ::LanguageTypedString)
         :en ::enLanguageTypedString
         :tail (s/* ::LanguageTypedString)))

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

;; TODO: Check this: XSD says 0-999 for ISCED, original spec def was 4
;; digits.
(s/def ::fieldsOfStudy (re-spec #"0*\d{1,3}"))
(s/def ::learningOutcomes (s/coll-of ::LanguageTypedStrings))
(s/def ::level enums/levels)
(s/def ::levelOfQualification #{"1" "2" "3" "4" "4+" "5" "6" "7" "8"})
(s/def ::modeOfDelivery enums/modesOfDelivery)
(s/def ::sector enums/sectors)

(defn level-sector-map-to-rio?
  "True if we can map the given level and sector to RIO."
  [{:keys [level sector]}]
  (some? (rio/level-sector-mapping level sector)))

;; Address
(s/def ::additional any?)
(s/def ::addressType #{"postal" "visit" "deliveries" "billing" "teaching"})
(s/def ::city string?)
(s/def ::countryCode string?)
(s/def ::geolocation (s/keys :req-un [::latitude ::longitude]))
(s/def ::latitude number?)
(s/def ::longitude number?)
(s/def ::postalCode string?)
(s/def ::street string?)
(s/def ::streetNumber any?)
(s/def ::address (s/keys :req-un [::addressType]
                         :opt-un [::additional
                                  ::city
                                  ::countryCode
                                  ::geolocation
                                  ::postalCode
                                  ::street
                                  ::streetNumber]))
(s/def ::addresses (s/coll-of ::address))
