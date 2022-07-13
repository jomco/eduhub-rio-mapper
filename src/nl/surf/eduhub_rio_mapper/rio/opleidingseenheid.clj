(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.Opleidingseenheid :as-alias Opleidingseenheid]
            [nl.surf.eduhub-rio-mapper.rio.ParticuliereOpleiding :as-alias ParticuliereOpleiding]))

(def formal-document-mapping
  {"diploma"              "DIPLOMA"
   "certificate"          "CERTIFICAAT"
   "no official document" "GEEN_OFFICIEEL_DOCUMENT"
   "school advice"        "SCHOOLADVIES"
   "testimonial"          "GETUIGSCHRIFT"})

(def studielasteenheid-mapping
  {"contacttime" "CONTACTUUR"
   "ects"        "ECTS_PUNT"
   "sbu"         "SBU"
   "sp"          "STUDIEPUNT"
   "hour"        "UUR"})

(defn level-sector-mapping [level sector]
  {:pre [(some? level) (some? sector)]}
  (case level
    "undefined" "ONBEPAALD"
    "nt2-1" "NT2-I"
    "nt2-2" "NT2-II"
    (case sector
      "secondary vocational education"
      (case level
        "secondary vocational education" "MBO"
        "secondary vocational education 1" "MBO-1"
        "secondary vocational education 2" "MBO-2"
        "secondary vocational education 3" "MBO-3"
        "secondary vocational education 4" "MBO-4")

      "higher professional education"
      (case level
        "associate degree" "HBO-AD"
        "bachelor" "HBO-BA"
        "master" "HBO-MA"
        "doctoral" "HBO-PM"
        "undivided" "HBO-O")

      "university education"
      (case level
        "bachelor" "WO-BA"
        "master" "WO-MA"
        "doctoral" "WO-PM"
        "undivided" "WO-O"))))

(defn convert-from-education-specification
  [{:keys [abbreviation validFrom name description studyLoad formalDocument educationSpecificationId level sector]}]
  {:begindatum                    validFrom
   :naamLang                      (common/get-localized-value name ["nl-NL" "en-GB" "en-"])
   :naamKort                      abbreviation
   :internationaleNaam            (common/get-localized-value name ["en-"])
   :omschrijving                  (common/get-localized-value description ["nl-NL" "en-GB" "en-"])
   :studielast                    (studyLoad :value)
   :studielasteenheid             (studielasteenheid-mapping (studyLoad :studyLoadUnit))
   :soort                         "OPLEIDING"
   :eigenOpleidingseenheidSleutel educationSpecificationId
   :waardedocumentsoort           (formal-document-mapping formalDocument)
   :niveau                        (level-sector-mapping level sector)})

(s/def ::Opleidingseenheid/begindatum ::common/date)
(s/def ::Opleidingseenheid/buitenlandsePartner string?)
(s/def ::Opleidingseenheid/eigenOpleidingsEenheidSleutel string?)
(s/def ::Opleidingseenheid/eindDatum ::common/date)
(s/def ::Opleidingseenheid/internationaleNaam string?)
(s/def ::Opleidingseenheid/naamKort string?)
(s/def ::Opleidingseenheid/naamLang string?)
(s/def ::Opleidingseenheid/omschrijving string?)
(s/def ::Opleidingseenheid/opleidingseenheidcode string?)
(s/def ::Opleidingseenheid/studielast number?)
(s/def ::Opleidingseenheid/studielasteenheid string?)
(s/def ::Opleidingseenheid/waardedocumentsoort string?)

(s/def ::Opleidingseenheid
  (s/keys :req-un [::Opleidingseenheid/begindatum
                   ::Opleidingseenheid/naamLang]
          :opt-un [::Opleidingseenheid/buitenlandsePartner
                   ::Opleidingseenheid/eigenOpleidingsEenheidSleutel
                   ::Opleidingseenheid/eindDatum
                   ::Opleidingseenheid/internationaleNaam
                   ::Opleidingseenheid/naamKort
                   ::Opleidingseenheid/omschrijving
                   ::Opleidingseenheid/opleidingseenheidcode
                   ::Opleidingseenheid/studielast
                   ::Opleidingseenheid/studielasteenheid
                   ::Opleidingseenheid/waardedocumentsoort]))

(s/def ::Opleidingseenheid/soort string?)
(s/def ::Opleidingseenheid/niveau string?)

;; If type is program and the subType field is absent soort will be set to OPLEIDING.
;; If type is program and subType is set to variant, soort will be set to VARIANT.
(s/def ::rio/HoOpleiding
  (s/merge ::Opleidingseenheid
           (s/keys :req-un [::Opleidingseenheid/soort
                            ::Opleidingseenheid/niveau])))

;; Just HoOnderwijseenheidPeriode
(s/def ::HoOnderwijseenheid
  (s/merge ::Opleidingseenheid
           (s/keys)))

;; If type is cluster, this will always be set to HOEC.
;; Also HoOnderwijseenhedenclusterPeriode
(s/def ::HoOnderwijseenhedencluster
  (s/merge ::Opleidingseenheid
           (s/keys :req-un [::Opleidingseenheid/soort])))

(s/def ::ParticuliereOpleiding/eqf string?)
(s/def ::ParticuliereOpleiding/nlqf string?)
(s/def ::ParticuliereOpleiding/categorie (s/coll-of string?))

(s/def ::ParticuliereOpleiding
  (s/merge ::Opleidingseenheid
           (s/keys :req-un [::ParticuliereOpleiding/categorie]
                   :opt-un [::ParticuliereOpleiding/eqf
                            ::ParticuliereOpleiding/nlqf])))
