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
  (when duration
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
          {:eenheid "M" :omvang (.toTotalMonths p)})))))

;; TODO
(defn program->aangeboden-ho-opleidingsonderdeel
  "Only intended for programs whose education specification has type course."
  [program]
  program)

;; TODO
(defn program->aangeboden-particuliere-opleiding
  "Only intended for programs whose education specification has type privateProgram."
  [program]
  program)

(defn- remove-nil-values [hm] (into {} (remove (comp nil? second) hm)))

;;;
(defn program->aangeboden-ho-opleiding
  "Only intended for programs whose education specification has type program."
  [{:keys [consumers duration programId validFrom educationSpecification firstStartDate name abbreviation description validTo teachingLanguage modeOfStudy link]}]
  (let [rio-consumer (some->> consumers (filter #(= (:consumerKey %) "rio")) first)
        duration     (parse-duration duration)]
    (remove-nil-values
      {
       ; required
       :aangebodenOpleidingCode             programId
       :begindatum                          validFrom
       :onderwijsaanbiederCode              (:educationOffererCode rio-consumer)
       :opleidingeenheidSleutel             educationSpecification
       :opleidingsduurEenheid               (:eenheid duration)
       :opleidingsduurOmvang                (:omvang duration)
       :toestemmingDeelnameSTAP             (:consentParticipationSTAP rio-consumer)
       ; optional
       :eersteInstroomDatum                 firstStartDate
       :eigenNaamAangebodenOpleiding        (common/get-localized-value name ["nl-NL"])
       :eigenNaamInternationaal             (common/get-localized-value name ["en-"])
       :eigenNaamKort                       abbreviation
       :eigenOmschrijving                   (common/get-localized-value description ["nl-NL"])
       :einddatum                           validTo
       :onderwijslocatieCode                (:educationLocationCode rio-consumer)
       :propedeutischeFase                  (:propaedeuticPhase rio-consumer propaedeutic-mapping)
       :studiekeuzecheck                    (:studyChoiceCheck rio-consumer studychoice-check-mapping)
       :voertaal                            teachingLanguage
       :vorm                                (mode-of-study-mapping modeOfStudy)
       :website                             link
       ; multiple
       :buitenlandsePartner                 (or (:foreignPartners rio-consumer) [])
       :samenwerkendeOnderwijsaanbiedercode (or (:jointPartnerCodes rio-consumer) [])})))

(defn program->aangeboden-opleiding
  "Converts a program into the right kind of AangebodenOpleiding."
  [program education-specification-type]
  (let [converter (case education-specification-type
                    "program" program->aangeboden-ho-opleiding
                    "privateProgram" program->aangeboden-particuliere-opleiding
                    "course" program->aangeboden-ho-opleidingsonderdeel
                    "cluster" #(throw RuntimeException))]
    (converter program)))

(defn course->aangeboden-ho-opleidingsonderdeel
  [{:keys [courseId validFrom educationSpecification firstStartDate name abbreviation description validTo teachingLanguage link consumers duration]}]
  (let [rio-consumer (some->> consumers (filter #(= (:consumerKey %) "rio")) first)
        duration     (some-> duration parse-duration)]
    (->>
      {; required
       :aangebodenOpleidingCode             courseId
       :begindatum                          validFrom
       :onderwijsaanbiederCode              (:educationOffererCode rio-consumer)
       :opleidingeenheidSleutel             educationSpecification
       :opleidingsduurEenheid               (:eenheid duration)
       :opleidingsduurOmvang                (:omvang duration)
       :toestemmingDeelnameSTAP             (:consentParticipationSTAP rio-consumer)
       ; optional
       :eersteInstroomDatum                 firstStartDate
       :eigenNaamAangebodenOpleiding        (common/get-localized-value name ["nl-NL"])
       :eigenNaamInternationaal             (common/get-localized-value name ["en-"])
       :eigenNaamKort                       abbreviation
       :eigenOmschrijving                   (common/get-localized-value description ["nl-NL"])
       :einddatum                           validTo
       :onderwijslocatieCode                (:educationLocationCode rio-consumer)
       :voertaal                            teachingLanguage
       :website                             link
       ; multiple
       :buitenlandsePartner                 (or (:foreignPartners rio-consumer) [])
       :samenwerkendeOnderwijsaanbiedercode (or (:jointPartnerCodes rio-consumer) [])}
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
