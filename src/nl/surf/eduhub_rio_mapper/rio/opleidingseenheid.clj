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

(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid
  (:require [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio]))

(def ^:private education-specification-type-mapping
  {"course"         "hoOnderwijseenheid"
   "program"        "hoOpleiding"
   "privateProgram" "particuliereOpleiding"
   "cluster"        "hoOnderwijseenhedencluster"})

(defn- program-subtype-mapping [consumers]
  (when-let [rio-consumer (some->> consumers (filter #(= (:consumerKey %) "rio")) first)]
    (when (= "variant" (:educationSpecificationSubType rio-consumer)) "VARIANT")))

(defn- soort-mapping [{:keys [educationSpecificationType consumers]}]
  (case educationSpecificationType
    "cluster" "HOEC"
    "program" (or (program-subtype-mapping consumers) "OPLEIDING")
    nil))

(defn- education-specification-timeline-override-adapter
  [{:keys [abbreviation description formalDocument name studyLoad validFrom] :as eduspec}]
  (fn [pk]
    (case pk
      :begindatum validFrom
      :internationaleNaam (common/get-localized-value name)
      :naamKort abbreviation
      :naamLang (common/get-localized-value name ["nl-NL" "nl"])
      :omschrijving (common/get-localized-value description ["nl-NL" "nl"])
      :studielast (if (= "VARIANT" (soort-mapping eduspec)) nil (:value studyLoad))
      :studielasteenheid (rio/ooapi-mapping "studielasteenheid" (:studyLoadUnit studyLoad))
      :waardedocumentsoort (rio/ooapi-mapping "waardedocumentsoort" formalDocument))))

(def ^:private mapping-eduspec->opleidingseenheid
  {:eigenOpleidingseenheidSleutel :educationSpecificationId
   :opleidingseenheidcode         :rioId})

(defn- education-specification-adapter
  [{:keys [validFrom validTo formalDocument level levelOfQualification sector fieldsOfStudy timelineOverrides] :as eduspec}
   {:keys [category] :as _rio-consumer}]
  (fn [opl-eenh-attr-name]
    (let [periods     (map #(assoc (:educationSpecification %)
                              :validFrom (:validFrom %)
                              :validTo (:validTo %))
                           timelineOverrides)
          translation (mapping-eduspec->opleidingseenheid opl-eenh-attr-name)]
      (if translation
        (translation eduspec)
        (case opl-eenh-attr-name
          ;; The main education specification object represents the current situation, while the timelineOverrides
          ;; specify past and future states. However, in RIO's opleidingseenheid, the main object's begindatum and
          ;; einddatum represent the entire lifespan of an opleidingseenheid, while its periodes represent each
          ;; temporary state. Therefore, we calculate the lifespan of an opleidingseenheid below.
          :begindatum (first (sort (conj (map :validFrom timelineOverrides) validFrom)))
          :einddatum (last (sort (conj (map :validTo timelineOverrides) validTo)))
          :ISCED (rio/narrow-isced fieldsOfStudy)
          :categorie (rio/ooapi-mapping "categorie" category)
          :eqf (rio/ooapi-mapping "eqf" levelOfQualification)
          :niveau (rio/level-sector-mapping level sector)
          :nlqf (rio/ooapi-mapping "nlqf" levelOfQualification)
          ;; The empty map {} represents the main object without adaptations from timelineOverrides.
          :periodes (->> (conj periods {})
                         (map #(merge eduspec %))
                         (mapv education-specification-timeline-override-adapter))
          :soort (soort-mapping eduspec)
          :waardedocumentsoort (rio/ooapi-mapping "waardedocumentsoort" formalDocument))))))

(defn education-specification->opleidingseenheid
  "Converts a education specification into the right kind of Opleidingseenheid."
  [eduspec]
  (-> (education-specification-adapter eduspec (common/extract-rio-consumer (:consumers eduspec)))
      rio/wrapper-periodes-cohorten
      (rio/->xml (education-specification-type-mapping (:educationSpecificationType eduspec)))))
