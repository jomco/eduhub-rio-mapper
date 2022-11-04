(ns nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding
  (:require [clojure.string :as string]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import [java.time Period Duration]))

(defn- parse-duration [duration]
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

(defn- extract-rio-consumer
  "Find the first consumer with a consumerKey equal to 'rio' or return nil."
  [consumers]
  (some->> consumers
           (filter #(= (:consumerKey %) "rio"))
           first))

(def ^:private education-specification-type-mapping
  {"course"         "aangebodenHOOpleidingsonderdeel"
   "cluster"        "aangebodenHOOpleidingsonderdeel"
   "program"        "aangebodenHOOpleiding"
   "privateProgram" "aangebodenParticuliereOpleiding"})

(def ^:private mapping-course-program->aangeboden-opleiding
  {:begindatum [:validFrom false]
   :buitenlandsePartner [:foreignPartners true]
   :eersteInstroomDatum [:firstStartDate false]
   :einddatum [:validTo false]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode false]
   :opleidingseenheidSleutel [::rio/opleidingscode false]
   :toestemmingDeelnameSTAP [:consentParticipationSTAP true]
   :voertaal [:teachingLanguage false]})

(def ^:private mapping-offering->cohort
  {:beginAanmeldperiode :enrollStartDate
   :cohortcode :offeringId
   :deelnemersplaatsen :maxNumberStudents
   :einddatum :validTo
   :eindeAanmeldperiode :enrollEndDate
   :toelichtingVereisteToestemming :explanationRequiredPermission})

(defn- course-program-timeline-override-adapter
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
      :website link)))

(defn- course-program-offering-adapter
  [{:keys [consumers startDate modeOfDelivery priceInformation
           flexibleEntryPeriodStart flexibleEntryPeriodEnd] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]
         :as   _rio-consumer} (extract-rio-consumer consumers)]
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
                       priceInformation))))))

(defn- course-program-adapter
  "Given a course or program, a rio-consumer object and an id, return a function.
   This function, given a attribute name from the RIO namespace, returns the corresponding value from the course or program,
   translated if necessary to the RIO domain."
  [{:keys [offerings level modeOfStudy sector timelineOverrides fieldsOfStudy] :as course-program}
   {:keys [duration] :as rio-consumer}
   id
   opleidingscode]
  (let [duration-map (some-> duration parse-duration)]
    (fn [k] {:pre [(keyword? k)]}
      (if (= k :opleidingseenheidSleutel)
        opleidingscode
        (if-let [[translation consumer] (mapping-course-program->aangeboden-opleiding k)]
          (if (rio/ooapi-mapping? (name k))
            (rio/ooapi-mapping (name k) (translation (if consumer rio-consumer course-program)))
            (translation (if consumer rio-consumer course-program)))
          (case k
            :ISCED (rio/narrow-isced fieldsOfStudy)
            :aangebodenOpleidingCode id
            :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                          :opleidingsduurOmvang (:omvang duration-map)})
            :niveau (rio/level-sector-mapping level sector)
            :vorm (rio/ooapi-mapping "vorm" modeOfStudy)

            :cohorten (mapv #(course-program-offering-adapter %)
                            offerings)

            :periodes (mapv #(course-program-timeline-override-adapter % rio-consumer)
                            (map #(merge % course-program) (conj timelineOverrides {})))

            ;; These are in the xsd but ignored by us
            :eigenAangebodenOpleidingSleutel id ;; resolve to the id
            :opleidingserkenningSleutel nil
            :voVakerkenningSleutel nil))))))

(defn program->aangeboden-opleiding
  "Converts a program into the right kind of AangebodenOpleiding."
  [program education-specification-type opleidingscode]
  (let [object-name (education-specification-type-mapping education-specification-type)
        rio-consumer (extract-rio-consumer (:consumers program))]
    (rio/->xml (course-program-adapter program rio-consumer (:programId program) opleidingscode) object-name)))

(defn course->aangeboden-opleiding
  "Converts a program into the right kind of AangebodenOpleiding."
  [course opleidingscode]
  (let [rio-consumer (extract-rio-consumer (:consumers course))]
    (rio/->xml (course-program-adapter course rio-consumer (:courseId course) opleidingscode) "aangebodenHOOpleidingsonderdeel")))
