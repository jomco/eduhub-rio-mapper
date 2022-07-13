(ns nl.surf.eduhub-rio-mapper.rio
  (:require [clojure.data.xml :as clj-xml]
            [nl.surf.eduhub-rio-mapper.xml-validator :as xml]))

(def raadplegen-xsd "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd")
(def beheren-xsd "DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd")
(def raadplegen-validator (xml/create-validation-fn raadplegen-xsd))
(def beheren-validator (xml/create-validation-fn beheren-xsd))

(defn is-valid-xml? [xml-string xsd-validator] (xsd-validator xml-string))

;; Helpers

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

(defn generate-hoopleiding-periode
  [periode]
  [:duo:hoOpleidingPeriode
    [:duo:begindatum (periode :begindatum)]
    [:duo:naamLang (periode :naamLang)]
    (when-let [v (periode :naamKort)] [:duo:naamKort v])
    (when-let [v (periode :internationaleNaam)] [:duo:internationaleNaam v])
    (when-let [v (periode :omschrijving)] [:duo:omschrijving v])
    [:duo:studielast (periode :studielast)]
    [:duo:studielasteenheid (periode :studielasteenheid)]])

(defn generate-xml-hoopleiding
  [opl-eenh]
  [:duo:hoOpleiding
    [:duo:begindatum (opl-eenh :begindatum)]
    (kenmerken "soort" :enum (opl-eenh :soort))
    (kenmerken "eigenOpleidingseenheidSleutel" :string (opl-eenh :eigenOpleidingseenheidSleutel))
    (generate-hoopleiding-periode opl-eenh)
    [:duo:waardedocumentsoort (opl-eenh :waardedocumentsoort)]
    [:duo:niveau (opl-eenh :niveau)]])

(defn generate-docroot [opl-eenh]
  [:duo:aanleveren_opleidingseenheid_request {:xmlns:duo "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4"}
    [:duo:identificatiecodeBedrijfsdocument "26330d25-7887-4319-aab6-752463650faf"]
    [:duo:verzendendeInstantie "mijnSchool"]
    [:duo:ontvangendeInstantie "DUO"]
    [:duo:datumTijdBedrijfsdocument "2022-03-24T12:01:42Z"]
    (generate-xml-hoopleiding opl-eenh)])

(defn xml-str [opl-eenh]
  (-> opl-eenh
      (generate-docroot)
      (clj-xml/sexp-as-element)
      (clj-xml/indent-str)))
