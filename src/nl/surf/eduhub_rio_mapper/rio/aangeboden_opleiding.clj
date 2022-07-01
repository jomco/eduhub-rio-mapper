(ns nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding.AangebodenOpleiding :as-alias AangebodenOpleiding]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding.AangebodenHoOpleiding :as-alias AangebodenHoOpleiding]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding.AangebodenParticuliereOpleiding :as-alias AangebodenParticuliereOpleiding])
  (:import (java.time Period Duration)))

(def propaedeutic-mapping
  {"no_propaedeutic_phase" "GEEN_PROPEDEUTISCHE_FASE"
   "propaedeutic_phase_exam" "PROPEDEUTISCHE_FASE_EXAMEN"
   "propaedeutic_phase_no_exam" "PROPEDEUTISCHE_FASE_ZONDER_EXAMEN"})

(def studychoice-check-mapping
  {"no_study_choice_check" "GEEN_STUDIEKEUZE_CHECK"
   "study_choice_check_available" "STUDIEKEUZE_CHECK_VAN_TOEPASSING"
   "study_choice_check_mandatory" "STUDIEKEUZE_CHECK_VERPLICHT"})

;; TODO self-paced missing, cannot be mapped to RIO
(def mode-of-study-mapping
  {"full-time" "VOLTIJD"
   "part-time" "DEELTIJD"
   "dual training" "DUAAL"})

(defn parse-duration [duration]
  (if (string/includes? duration "T")
    ;; If it contains a T, we treat it as a time period, and count in hours.
    (let [d (Duration/parse duration)]
      {:eenheid "U" :omvang (.toHours d)})
    (let [p (Period/parse duration)
          months (.getMonths p)]
      (cond
        ;; When less than a month, use days.
        (zero? (.toTotalMonths p))
        {:eenheid "D" :omvang (.getDays p)}
        ;; Whole number of years, use years.
        (zero? months)
        {:eenheid "J" :omvang (.getYears p)}
        ;; Otherwise use months.
        :else
        {:eenheid "M" :omvang (.toTotalMonths p)}))))

;; TODO
(defn program->aangeboden-ho-opleidingsonderdeel
  "Only intended for programs whose education specification has type course."
  [program]
  nil)

;; TODO
(defn program->aangeboden-particuliere-opleiding
  "Only intended for programs whose education specification has type privateProgram."
  [program]
  nil)

(defn- remove-nil-values [hm] (into {} (remove (comp nil? second) hm)))

;;;
(defn program->aangeboden-ho-opleiding
  "Only intended for programs whose education specification has type program."
  [program]
  (let [rio-consumer (some->> program :consumers (filter #(= (:consumerKey %) "rio")) first)
        duration     (some-> program :duration parse-duration)]
    (remove-nil-values
      {
       ; required
       :aangebodenOpleidingCode             (program :programId)
       :begindatum                          (program :validFrom)
       :onderwijsaanbiederCode              (some-> rio-consumer :educationOffererCode)
       :opleidingeenheidSleutel             (program :educationSpecification)
       :opleidingsduurEenheid               (some-> duration :eenheid)
       :opleidingsduurOmvang                (some-> duration :omvang)
       :toestemmingDeelnameSTAP             (some-> rio-consumer :consentParticipationSTAP)
       ; optional
       :eersteInstroomDatum                 (program :firstStartDate)
       :eigenNaamAangebodenOpleiding        (common/get-localized-value (program :name) ["nl-NL"])
       :eigenNaamInternationaal             (common/get-localized-value (program :name) ["en-"])
       :eigenNaamKort                       (program :abbreviation)
       :eigenOmschrijving                   (common/get-localized-value (program :description) ["nl-NL"])
       :einddatum                           (program :validTo)
       :onderwijslocatieCode                (some-> rio-consumer :educationLocationCode)
       :propedeutischeFase                  (some-> rio-consumer :propaedeuticPhase propaedeutic-mapping)
       :studiekeuzecheck                    (some-> rio-consumer :studyChoiceCheck studychoice-check-mapping)
       :voertaal                            (program :teachingLanguage)
       :vorm                                (-> program :modeOfStudy mode-of-study-mapping)
       :website                             (program :link)
       ; multiple
       :buitenlandsePartner                 (or (some-> rio-consumer :foreignPartners) [])
       :samenwerkendeOnderwijsaanbiedercode (or (some-> rio-consumer :jointPartnerCodes) [])})))

(defn program->aangeboden-opleiding
  "Converts a program into the right kind of AangebodenOpleiding."
  [program education-specification-type]
  (-> program
      (case education-specification-type
            "program" program->aangeboden-ho-opleiding
            "privateProgram" program->aangeboden-particuliere-opleiding
            "course" program->aangeboden-ho-opleidingsonderdeel
            "cluster" #(throw RuntimeException))))

(defn course->aangeboden-ho-opleidingsonderdeel
  [course]
  (let [rio-consumer (some->> course :consumers (filter #(= (:consumerKey %) "rio")) first)
        duration     (some-> course :duration parse-duration)]
    (->>
      {; required
       :aangebodenOpleidingCode             (course :courseId)
       :begindatum                          (course :validFrom)
       :onderwijsaanbiederCode              (some-> rio-consumer :educationOffererCode)
       :opleidingeenheidSleutel             (course :educationSpecification)
       :opleidingsduurEenheid               (some-> duration :eenheid)
       :opleidingsduurOmvang                (some-> duration :omvang)
       :toestemmingDeelnameSTAP             (some-> rio-consumer :consentParticipationSTAP)
       ; optional
       :eersteInstroomDatum                 (course :firstStartDate)
       :eigenNaamAangebodenOpleiding        (common/get-localized-value (course :name) ["nl-NL"])
       :eigenNaamInternationaal             (common/get-localized-value (course :name) ["en-"])
       :eigenNaamKort                       (course :abbreviation)
       :eigenOmschrijving                   (common/get-localized-value (course :description) ["nl-NL"])
       :einddatum                           (course :validTo)
       :onderwijslocatieCode                (some-> rio-consumer :educationLocationCode)
       :voertaal                            (course :teachingLanguage)
       :website                             (course :link)
       ; multiple
       :buitenlandsePartner                 (or (some-> rio-consumer :foreignPartners) [])
       :samenwerkendeOnderwijsaanbiedercode (or (some-> rio-consumer :jointPartnerCodes) [])}
      (remove (comp nil? second))
      (into {}))))

(s/def ::AangebodenOpleiding/aangebodenOpleidingCode string?)
(s/def ::AangebodenOpleiding/afwijkendeOpleidingsduurEenheid string?)
(s/def ::AangebodenOpleiding/afwijkendeOpleidingsduurOmvang number?)
(s/def ::AangebodenOpleiding/begindatum ::common/date)
(s/def ::AangebodenOpleiding/buitenlandsePartner (s/coll-of string?))
(s/def ::AangebodenOpleiding/eersteInstroomDatum ::common/date)
(s/def ::AangebodenOpleiding/eigenNaamAangebodenOpleiding string?)
(s/def ::AangebodenOpleiding/eigenNaamInternationaal string?)
(s/def ::AangebodenOpleiding/eigenNaamKort string?)
(s/def ::AangebodenOpleiding/eigenOpleidingsEenheidSleutel string?)
(s/def ::AangebodenOpleiding/eindDatum ::common/date)
(s/def ::AangebodenOpleiding/naamKort string?)
(s/def ::AangebodenOpleiding/omschrijving string?)
(s/def ::AangebodenOpleiding/onderwijsaanbiederCode string?)
(s/def ::AangebodenOpleiding/onderwijslocatieCode string?)
(s/def ::AangebodenOpleiding/opleidingsduurEenheid string?)
(s/def ::AangebodenOpleiding/opleidingsduurOmvang number?)
(s/def ::AangebodenOpleiding/opleidingseenheidcode string?)
(s/def ::AangebodenOpleiding/samenwerkendeOnderwijsaanbiedercode (s/coll-of string?))
(s/def ::AangebodenOpleiding/studielast number?)
(s/def ::AangebodenOpleiding/studielasteenheid string?)
(s/def ::AangebodenOpleiding/toestemmingDeelnameSTAP string?)
(s/def ::AangebodenOpleiding/voertaal string?)
(s/def ::AangebodenOpleiding/waardedocumentsoort string?)
(s/def ::AangebodenOpleiding/website string?)

(s/def ::AangebodenOpleiding
  (s/keys :req-un [::AangebodenOpleiding/aangebodenOpleidingCode
                   ::AangebodenOpleiding/begindatum]
          :opt-un [::AangebodenOpleiding/afwijkendeOpleidingsduurEenheid
                   ::AangebodenOpleiding/afwijkendeOpleidingsduurOmvang
                   ::AangebodenOpleiding/eersteInstroomDatum
                   ::AangebodenOpleiding/einddatum
                   ::AangebodenOpleiding/eigenNaamAangebodenOpleiding
                   ::AangebodenOpleiding/eigenNaamInternationaal
                   ::AangebodenOpleiding/eigenNaamKort
                   ::AangebodenOpleiding/eigenOmschrijving
                   ::AangebodenOpleiding/onderwijsaanbiederCode
                   ::AangebodenOpleiding/onderwijslocatieCode
                   ::AangebodenOpleiding/opleidingsduurEenheid
                   ::AangebodenOpleiding/opleidingsduurOmvang
                   ::AangebodenOpleiding/opleidingeenheidSleutel
                   ::AangebodenOpleiding/samenwerkendeOnderwijsaanbiedercode
                   ::AangebodenOpleiding/studielast
                   ::AangebodenOpleiding/studielasteenheid
                   ::AangebodenOpleiding/toestemmingDeelnameSTAP
                   ::AangebodenOpleiding/voertaal
                   ::AangebodenOpleiding/waardedocumentsoort
                   ::AangebodenOpleiding/website]))

(s/def ::AangebodenHoOpleiding/deficientie string?)
(s/def ::AangebodenHoOpleiding/eisenWerkzaamheden string?)
(s/def ::AangebodenHoOpleiding/internationaleNaamDuits string?)
(s/def ::AangebodenHoOpleiding/propedeutischeFase string?)
(s/def ::AangebodenHoOpleiding/studiekeuzecheck string?)
(s/def ::AangebodenHoOpleiding/versneldTraject string?)
(s/def ::AangebodenHoOpleiding/vorm string?)

;; TODO timeline overrides
(s/def ::AangebodenHoOpleiding
  (s/merge ::AangebodenOpleiding
           (s/keys :req-un [::AangebodenHoOpleiding/propedeutischeFase
                            ::AangebodenHoOpleiding/studiekeuzecheck
                            ::AangebodenHoOpleiding/vorm]
                   :opt-un [::AangebodenOpleiding/buitenlandsePartner
                            ::AangebodenOpleiding/eigenNaamKort
                            ::AangebodenHoOpleiding/deficientie
                            ::AangebodenHoOpleiding/eisenWerkzaamheden
                            ::AangebodenHoOpleiding/internationaleNaamDuits
                            ::AangebodenHoOpleiding/versneldTraject])))

;; TODO timeline overrides
(s/def ::AangebodenHoOpleidingsonderdeel
  (s/merge ::AangebodenOpleiding
           (s/keys :opt-un [::AangebodenOpleiding/buitenlandsePartner
                            ::AangebodenOpleiding/eigenNaamKort])))

(s/def ::AangebodenParticuliereOpleiding/niveau string?)

;; TODO timeline overrides
(s/def ::AangebodenParticuliereOpleiding
  (s/merge ::AangebodenOpleiding
           (s/keys :opt-un [::AangebodenParticuliereOpleiding/niveau])))
