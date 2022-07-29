(ns nl.surf.eduhub-rio-mapper.rio
  (:require [clojure.data.xml :as clj-xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-validator :as validator])
  (:import [java.io PushbackReader]))

(def raadplegen-xsd "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd")
(def beheren-xsd "DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd")
(def raadplegen-validator (validator/create-validation-fn raadplegen-xsd))
(def beheren-validator (validator/create-validation-fn beheren-xsd))

(def specifications (edn/read (PushbackReader. (io/reader (io/resource "ooapi-mappings.edn")))))

(defn ooapi-mapping [name key]
  (get-in specifications [:mappings name key]))

(def xsd-beheren (edn/read (PushbackReader. (io/reader (io/resource "beheren-schema.edn")))))

(defn is-valid-xml? [xml-string xsd-validator] (xsd-validator xml-string))

;; Helpers

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

(def type-mapping
  {:date    :duo:kenmerkwaardeDatum
   :string  :duo:kenmerkwaardeTekst
   :enum    :duo:kenmerkwaardeEnumeratiewaarde
   :number  :duo:kenmerkwaardeGetal
   :boolean :duo:kenmerkwaardeBoolean})

(defn kenmerken [name type value]
  (when value
     [:duo:kenmerken
       [:duo:kenmerknaam name]
       [(type-mapping type) value]]))

;; XML generation

(defn generate-xml-hoonderwijseenheid-periode
  [periode]
  [:duo:hoOnderwijseenheidPeriode
   [:duo:begindatum (periode :begindatum)]
   [:duo:naamLang (periode :naamLang)]
   (when-let [v (periode :naamKort)] [:duo:naamKort v])
   (when-let [v (periode :internationaleNaam)] [:duo:internationaleNaam v])
   (when-let [v (periode :omschrijving)] [:duo:omschrijving v])])

(defn generate-hoopleiding-periode
  [periode]
  [:duo:hoOpleidingPeriode
    [:duo:begindatum (periode :begindatum)]
    [:duo:naamLang (periode :naamLang)]
    (when-let [v (periode :naamKort)] [:duo:naamKort v])
    (when-let [v (periode :internationaleNaam)] [:duo:internationaleNaam v])
    (when-let [v (periode :omschrijving)] [:duo:omschrijving v])])
;; TODO Apparently HoOpleidingPeriode doesn't allow these, but that's not clear from the xsd or the ooapi rio consumer docs.
;[:duo:studielast (periode :studielast)]
;[:duo:studielasteenheid (periode :studielasteenheid)]

(defn generate-xml-hoonderwijseenheid [opl-eenh]
  [:duo:hoOnderwijseenheid
   [:duo:begindatum (opl-eenh :begindatum)]
   (kenmerken "soort" :enum (opl-eenh :soort))
   (kenmerken "eigenOpleidingseenheidSleutel" :string (opl-eenh :eigenOpleidingseenheidSleutel))
   (generate-hoopleiding-periode opl-eenh)
   [:duo:waardedocumentsoort (opl-eenh :waardedocumentsoort)]
   [:duo:niveau (opl-eenh :niveau)]])

(defn generate-xml-hoopleiding
  [opl-eenh]
  [:duo:hoOpleiding
    [:duo:begindatum (opl-eenh :begindatum)]
    (kenmerken "soort" :enum (opl-eenh :soort))
    (kenmerken "eigenOpleidingseenheidSleutel" :string (opl-eenh :eigenOpleidingseenheidSleutel))
    (generate-hoopleiding-periode opl-eenh)
    [:duo:waardedocumentsoort (opl-eenh :waardedocumentsoort)]
    [:duo:niveau (opl-eenh :niveau)]])

(defn generate-aangeboden-ho-opleiding-periode [{:keys [begindatum propedeutischeFase studiekeuzecheck]}]
  [:duo:aangebodenHOOpleidingPeriode
   [:duo:begindatum begindatum]
   (kenmerken "propedeutischeFase" :enum propedeutischeFase)
   (kenmerken "studiekeuzecheck" :enum studiekeuzecheck)
   ])

(defn generate-aangeboden-ho-opleiding
  [{:keys [begindatum aangebodenOpleidingCode onderwijsaanbiedercode opleidingseenheidSleutel toestemmingDeelnameSTAP
           vorm] :as all}]
  [:duo:aangebodenHOOpleiding
   [:duo:aangebodenOpleidingCode aangebodenOpleidingCode]
   [:duo:onderwijsaanbiedercode onderwijsaanbiedercode]
   [:duo:begindatum begindatum]
   [:duo:opleidingseenheidSleutel opleidingseenheidSleutel]
   (generate-aangeboden-ho-opleiding-periode all)
   (kenmerken "toestemmingDeelnameSTAP" :enum toestemmingDeelnameSTAP)
   (kenmerken "vorm" :enum vorm)
   ;[:duo:opleidingsduurEenheid opleidingsduurEenheid]
   ;[:duo:opleidingsduurOmvang opleidingsduurOmvang]
   ])

(defn generate-docroot [opl-eenh]
  (conj (soap/request-body "aanleveren_opleidingseenheid" soap/beheren)
        (generate-xml-hoopleiding opl-eenh)))

(defn xml-str [opl-eenh]
  (-> opl-eenh
      (generate-docroot)
      (clj-xml/sexp-as-element)
      (clj-xml/indent-str)))
