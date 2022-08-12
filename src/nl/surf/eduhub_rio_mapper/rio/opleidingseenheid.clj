(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid
  (:require [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio]))

(def education-specification-type-mapping
  {"course"         "hoOnderwijseenheid"
   "program"        "hoOpleiding"
   "privateProgram" "particuliereOpleiding"
   "cluster"        "hoOnderwijseenhedencluster"})

(defn program-subtype-mapping [consumers]
  (when-let [rio-consumer (some->> consumers (filter #(= (:consumerKey %) "rio")) first)]
    (when (= "variant" (:educationSpecificationSubType rio-consumer)) "VARIANT")))

(defn soort-mapping [{:keys [educationSpecificationType consumers]}]
  (case educationSpecificationType
    "cluster" "HOEC"
    "program" (or (program-subtype-mapping consumers) "OPLEIDING")
    nil))

(defn education-specification-timeline-override-adapter
  [{:keys [abbreviation description formalDocument name studyLoad validFrom] :as _eduspec}]
  (fn [pk]
    (case pk
      :begindatum validFrom
      :internationaleNaam (common/get-localized-value name ["en-"])
      :naamKort abbreviation
      :naamLang (common/get-localized-value name ["nl-NL" "en-GB" "en-"])
      :omschrijving (common/get-localized-value description ["nl-NL" "en-GB" "en-"])
      :studielast (studyLoad :value)
      :studielasteenheid (rio/ooapi-mapping "studielasteenheid" (studyLoad :studyLoadUnit))
      :waardedocumentsoort (rio/ooapi-mapping "waardedocumentsoort" formalDocument)
      (println "missing for periode" pk))))

(def mapping-eduspec->opleidingseenheid
  {:begindatum                    :validFrom
   :einddatum                     :validTo
   :ISCED                         :fieldsOfStudy
   :eigenOpleidingseenheidSleutel :educationSpecificationId})

(defn education-specification-adapter
  [{:keys [category formalDocument level levelOfQualification sector timelineOverrides] :as eduspec}]
  (fn [opl-eenh-attr-name]
    (if-let [translation (mapping-eduspec->opleidingseenheid opl-eenh-attr-name)]
      (translation eduspec)
      (case opl-eenh-attr-name
        :categorie (rio/ooapi-mapping "categorie" category)
        :eqf (rio/ooapi-mapping "eqf" levelOfQualification)
        :niveau (rio/level-sector-mapping level sector)
        :nlqf (rio/ooapi-mapping "nlqf" levelOfQualification)
        :periodes (mapv education-specification-timeline-override-adapter
                        (map #(merge % eduspec) (conj timelineOverrides {})))
        :soort (soort-mapping eduspec)
        :waardedocumentsoort (rio/ooapi-mapping "waardedocumentsoort" formalDocument)
        (println "missing for opleidingseenheid" opl-eenh-attr-name)))))

(defn education-specification->opleidingseenheid
  "Converts a program into the right kind of Opleidingseenheid."
  [eduspec]
  (let [object-name (education-specification-type-mapping (:educationSpecificationType eduspec))]
    (rio/->xml (education-specification-adapter eduspec) object-name)))