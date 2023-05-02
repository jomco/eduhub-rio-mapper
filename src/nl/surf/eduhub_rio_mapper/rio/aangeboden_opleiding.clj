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

(ns nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import [java.time Period Duration]))

(defn- parse-duration [duration]
  (when duration
    (if (str/includes? duration "T")
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

(def education-specification-type-mapping
  {"course"         "aangebodenHOOpleidingsonderdeel"
   "cluster"        "aangebodenHOOpleidingsonderdeel"
   "program"        "aangebodenHOOpleiding"
   "privateProgram" "aangebodenParticuliereOpleiding"})

(def ^:private mapping-course-program->aangeboden-opleiding
  {:rioId [:aangebodenOpleidingCode false]
   :buitenlandsePartner [:foreignPartners true]
   :eersteInstroomDatum [:firstStartDate false]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode true]
   :opleidingseenheidSleutel [::rio/opleidingscode false]
   :toestemmingDeelnameSTAP [:consentParticipationSTAP true]
   :voertaal [:teachingLanguage false]})

(def ^:private mapping-offering->cohort
  {:beginAanmeldperiode :enrollStartDate
   :deelnemersplaatsen :maxNumberStudents
   :einddatum :endDate
   :eindeAanmeldperiode :enrollEndDate
   :toelichtingVereisteToestemming :explanationRequiredPermission})

(defn- course-program-timeline-override-adapter
  [{:keys [name description validFrom abbreviation link consumers] :as _periode}]
  (let [{:keys [acceleratedRoute deficiency foreignPartners jointPartnerCodes propaedeuticPhase
                requirementsActivities studyChoiceCheck]} (common/extract-rio-consumer consumers)]
    (fn [pk]
      (case pk
        :begindatum validFrom
        :buitenlandsePartner foreignPartners
        :deficientie (rio/ooapi-mapping "deficientie" deficiency)
        :eigenNaamAangebodenOpleiding (common/get-localized-value name ["nl-NL" "nl"])
        :eigenNaamInternationaal (common/get-localized-value-exclusive name ["en"])
        :eigenNaamKort abbreviation
        :eigenOmschrijving (common/get-localized-value description ["nl-NL" "nl"])
        :eisenWerkzaamheden (rio/ooapi-mapping "eisenWerkzaamheden" requirementsActivities)
        :internationaleNaamDuits (common/get-localized-value-exclusive name ["de"])
        :propedeutischeFase (rio/ooapi-mapping "propedeutischeFase" propaedeuticPhase)
        :samenwerkendeOnderwijsaanbiedercode jointPartnerCodes
        :studiekeuzecheck (rio/ooapi-mapping "studiekeuzecheck" studyChoiceCheck)
        :versneldTraject (rio/ooapi-mapping "versneldTraject" acceleratedRoute)
        :website link))))

(defn- course-program-offering-adapter
  [{:keys [consumers startDate modeOfDelivery priceInformation
           flexibleEntryPeriodStart flexibleEntryPeriodEnd] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]
         :as   _rio-consumer} (common/extract-rio-consumer consumers)]
    (fn [ck]
      (if-let [translation (mapping-offering->cohort ck)]
        (translation offering)
        (case ck
          :cohortcode (-> offering :primaryCode :code)
          :cohortstatus (rio/ooapi-mapping "cohortStatus" registrationStatus)
          :opleidingsvorm (first (filter seq (map #(rio/ooapi-mapping "opleidingsvorm" %) modeOfDelivery)))
          :toestemmingVereistVoorAanmelding (rio/ooapi-mapping "toestemmingVereistVoorAanmelding"
                                                               requiredPermissionRegistration)
          :bedrijfsopleiding nil    ; ignored
          :flexibeleInstroom (and flexibleEntryPeriodStart {:beginInstroomperiode flexibleEntryPeriodStart
                                                            :eindeInstroomperiode flexibleEntryPeriodEnd})
          :vastInstroommoment (when (nil? flexibleEntryPeriodStart) {:instroommoment startDate})
          :prijs (mapv (fn [h] {:soort (rio/ooapi-mapping "soort" (:costType h)) :bedrag (:amount h)})
                       priceInformation))))))

(defn- course-program-adapter
  "Given a course or program, a rio-consumer object and an id, return a function.
   This function, given a attribute name from the RIO namespace, returns the corresponding value from the course or program,
   translated if necessary to the RIO domain."
  [{:keys [validFrom validTo offerings level modeOfStudy sector fieldsOfStudy consumers timelineOverrides] :as course-program}
   opleidingscode
   ooapi-type]
  (let [rio-consumer (common/extract-rio-consumer consumers)
        duration-map (some-> rio-consumer :duration parse-duration)
        id           ((if (= :course ooapi-type) :courseId :programId) course-program)
        periods      (map #(assoc (ooapi-type %)
                             :validFrom (:validFrom %)
                             :validTo   (:validTo %))
                          timelineOverrides)]
    (fn [k] {:pre [(keyword? k)]}
      (if (= k :opleidingseenheidSleutel)
        opleidingscode
        (if-let [[translation consumer] (mapping-course-program->aangeboden-opleiding k)]
          (if (rio/ooapi-mapping? (name k))
            (rio/ooapi-mapping (name k) (translation (if consumer rio-consumer course-program)))
            (translation (if consumer rio-consumer course-program)))
          (case k
            ;; See opleidingseenheid for explanation of timelineOverrides and periods.
            :begindatum (first (sort (conj (map :validFrom timelineOverrides) validFrom)))
            :einddatum (last (sort (conj (map :validTo timelineOverrides) validTo)))
            :ISCED (rio/narrow-isced fieldsOfStudy)
            :aangebodenOpleidingCode id                     ; TODO use resolver
            :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                          :opleidingsduurOmvang (:omvang duration-map)})
            :niveau (rio/level-sector-mapping level sector)
            :vorm (rio/ooapi-mapping "vorm" modeOfStudy)

            :cohorten (mapv #(course-program-offering-adapter %)
                            offerings)

            ;; See opleidingseenheid for explanation of timelineOverrides and periods.
            :periodes (->> (conj periods course-program)
                           (mapv #(course-program-timeline-override-adapter %)))

            ;; These are in the xsd but ignored by us
            :eigenAangebodenOpleidingSleutel (some-> id str/lower-case) ;; resolve to the ooapi id
            :opleidingserkenningSleutel nil
            :voVakerkenningSleutel nil))))))

(defn ->aangeboden-opleiding
  "Converts a program or course into the right kind of AangebodenOpleiding."
  [course-program ooapi-type opleidingscode education-specification-type]
  (-> (course-program-adapter course-program opleidingscode ooapi-type)
      rio/wrapper-periodes-cohorten
      (rio/->xml (education-specification-type-mapping education-specification-type))))
