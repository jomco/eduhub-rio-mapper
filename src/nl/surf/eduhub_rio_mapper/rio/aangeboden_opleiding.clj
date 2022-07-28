(ns nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding
  (:require [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import [java.time Period Duration]))

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

(def education-specification-type-mapping
  {"course" "aangebodenHOOpleidingsonderdeel"
   "program" "aangebodenHOOpleiding"
   "privateProgram" "aangebodenParticuliereOpleiding"})

(def mapping-program->aangeboden-opleiding
  {:begindatum [:validFrom false]
   :buitenlandsePartner [:foreignPartners true]
   :eersteInstroomDatum [:firstStartDate false]
   :einddatum [:validTo false]
   :ISCED [:fieldsOfStudy false]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode false]
   :opleidingseenheidSleutel [:educationSpecification false]
   :toestemmingDeelnameSTAP [:consentParticipationSTAP true]
   :voertaal [:teachingLanguage false]})

(def mapping-offering->cohort
  {:beginAanmeldperiode :enrollStartDate
   :cohortcode :offeringId
   :deelnemersplaatsen :maxNumberStudents
   :einddatum :validTo
   :eindeAanmeldperiode :enrollEndDate
   :toelichtingVereisteToestemming :explanationRequiredPermission})

(defn course-program-timeline-override-adapter
  [{:keys [name description validFrom studyLoad abbreviation link] :as _periode}
   {:keys [acceleratedRoute deficiency foreignPartners jointPartnerCodes propaedeuticPhase
           requirementsActivities studyChoiceCheck] :as _rio-consumer}]
  (fn [pk]
    (case pk
      :begindatum validFrom
      :buitenlandsePartner foreignPartners
      :deficientie (rio/ooapi-mapping "deficientie" deficiency)
      :eigenNaamAangebodenOpleiding (common/get-localized-value name ["nl-NL"])
      :eigenNaamInternationaal (common/get-localized-value name ["en-"])
      :eigenNaamKort abbreviation
      :eigenOmschrijving (common/get-localized-value description ["nl-NL"])
      :eisenWerkzaamheden (rio/ooapi-mapping "eisenWerkzaamheden" requirementsActivities)
      :internationaleNaam (common/get-localized-value name ["en-"])
      :internationaleNaamDuits (common/get-localized-value name ["de-"])
      :naamLang (common/get-localized-value name ["nl-NL" "en-GB" "en-"])
      :omschrijving (common/get-localized-value description ["nl-NL" "en-GB" "en-"])
      :propedeutischeFase (rio/ooapi-mapping "propedeutischeFase" propaedeuticPhase)
      :samenwerkendeOnderwijsaanbiedercode jointPartnerCodes
      :studiekeuzecheck (rio/ooapi-mapping "studiekeuzecheck" studyChoiceCheck)
      :studielast (studyLoad :value)
      :versneldTraject (rio/ooapi-mapping "versneldTraject" acceleratedRoute)
      :website link
      (println "missing for periode" pk))))

(defn course-program-offering-adapter
  [{:keys [consumers startDate modeOfDelivery priceInformation
           flexibleEntryPeriodStart flexibleEntryPeriodEnd] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]
         :as   _rio-consumer} (some->> consumers (filter #(= (:consumerKey %) "rio")) first)]
    (fn [ck]
      (if-let [translation (mapping-offering->cohort ck)]
        (translation offering)
        (case ck
          :cohortstatus (rio/ooapi-mapping "cohortStatus" registrationStatus)
          :opleidingsvorm (first (filter seq (map #(rio/ooapi-mapping "opleidingsvorm" %) modeOfDelivery)))
          :toestemmingVereistVoorAanmelding (rio/ooapi-mapping "toestemmingVereistVoorAanmelding"
                                                               requiredPermissionRegistration)
          :bedrijfsopleiding nil    ; ignored
          :flexibeleInstroom (and flexibleEntryPeriodStart {:beginInstroomperiode flexibleEntryPeriodStart
                                                            :eindeInstroomperiode flexibleEntryPeriodEnd})
          :vastInstroommoment (when (nil? flexibleEntryPeriodStart) startDate)
          :prijs (mapv (fn [h] {:soort (rio/ooapi-mapping "soort" (:costType h)) :bedrag (:amount h)})
                       priceInformation)
          (println "missing for cohort" ck))))))

(defn course-program-adapter [{:keys [offerings level modeOfStudy sector timelineOverrides] :as program}
                       {:keys [duration] :as rio-consumer}
                       id]
  (let [duration-map (some-> duration parse-duration)]
    (fn [k]
      (if-let [[translation consumer] (mapping-program->aangeboden-opleiding k)]
        (if (rio/ooapi-mapping? (name k))
          (rio/ooapi-mapping k (translation (if consumer rio-consumer program)))
          (translation (if consumer rio-consumer program)))
        (case k
          :aangebodenOpleidingCode id
          :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                        :opleidingsduurOmvang (:omvang duration-map)})
          :niveau (rio/level-sector-mapping level sector)
          :vorm (rio/ooapi-mapping "vorm" modeOfStudy)

          :cohorten (mapv #(course-program-offering-adapter %)
                          offerings)

          :periodes (mapv #(course-program-timeline-override-adapter % rio-consumer)
                          (map #(merge % program) (conj timelineOverrides {})))

          ;; These are in the xsd but ignored by us
          :eigenAangebodenOpleidingSleutel nil
          :opleidingserkenningSleutel nil
          :voVakerkenningSleutel nil
          (println "missing for aangeboden opleiding" k))))))

(defn program->aangeboden-opleiding
  "Converts a program into the right kind of AangebodenOpleiding."
  [program education-specification-type]
  (let [object-name (education-specification-type-mapping education-specification-type)
        rio-consumer (some->> (:consumers program) (filter #(= (:consumerKey %) "rio")) first)]
    (rio/->xml (course-program-adapter program rio-consumer (:programId program)) object-name)))

(defn course->aangeboden-opleiding
  "Converts a program into the right kind of AangebodenOpleiding."
  [course]
  (let [rio-consumer (some->> (:consumers course) (filter #(= (:consumerKey %) "rio")) first)]
    (rio/->xml (course-program-adapter course rio-consumer (:courseId course)) "aangebodenHOOpleidingsonderdeel")))
