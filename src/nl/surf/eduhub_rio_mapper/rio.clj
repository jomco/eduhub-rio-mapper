(ns nl.surf.eduhub-rio-mapper.rio
  (:require [clojure.data.xml :as clj-xml]
            [nl.surf.eduhub-rio-mapper.xml-validator :as xml]
            [clojure.string :as string]))

(def raadplegen-xsd "doc/RIO-Webservicekoppeling-Beheren-en-Raadplegen/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd")
(def beheren-xsd "doc/RIO-Webservicekoppeling-Beheren-en-Raadplegen/DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd")
(def raadplegen-validator (xml/create-validation-fn raadplegen-xsd))
(def beheren-validator (xml/create-validation-fn beheren-xsd))

(defn is-valid-xml? [xml-string xsd-validator] (xsd-validator xml-string))

;; Mappings

(def formal-document-mapping
  {"diploma" "DIPLOMA"
   "certificate" "CERTIFICAAT"
   "no official document" "GEEN_OFFICIEEL_DOCUMENT"
   "school advice" "SCHOOLADVIES"
   "testimonial" "GETUIGSCHRIFT"})

(defn level-sector-mapping [level sector]
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

(defn get-localized-value
  "Get the first value of a LanguageTypedString where the language code matches the locale."
  [attr locales]
  (->> locales
       (map (fn [locale]
              (some-> (filter #(string/starts-with? (% :language) locale) attr)
                      (first)
                      (:value))))
       (filter identity)
       (first)))

;; XML generation

(defn generate-hoopleiding-periode
  [eduspec]
  [:duo:hoOpleidingPeriode
    [:duo:begindatum (eduspec :validFrom)]
    [:duo:naamLang (get-localized-value (eduspec :name) ["nl-NL" "en-GB" "en-"])]
    (when-let [v (eduspec :abbreviation)] [:duo:naamKort v])
    (when-let [v (get-localized-value (eduspec :name) ["en-"])] [:duo:internationaleNaam v])
    (when-let [v (get-localized-value (eduspec :description) ["nl-NL" "en-GB" "en-"])] [:duo:omschrijving v])
    (kenmerken "studielast" :number (get-in eduspec [:studyLoad :value]))
    (kenmerken "studielasteenheid" :string (get-in eduspec [:studyLoad :studyLoadUnit]))])

(defn generate-xml-hoopleiding
  [eduspec]
  [:duo:hoOpleiding
    [:duo:begindatum (eduspec :validFrom)]
    (kenmerken "soort" :enum "VARIANT")
    (kenmerken "eigenOpleidingseenheidSleutel" :string (eduspec :educationSpecificationId))
    (generate-hoopleiding-periode eduspec)
    [:duo:waardedocumentsoort (formal-document-mapping (eduspec :formalDocument))]
    [:duo:niveau (level-sector-mapping (eduspec :level) (eduspec :sector))]])

(defn apply-soap [docroot]
  [:SOAP-ENV:Envelope {:xmlns:SOAP-ENV "http://schemas.xmlsoap.org/soap/envelope/"}
    [:SOAP-ENV:Header {:xmlns:wsa "http://www.w3.org/2005/08/addressing"}
      [:wsa:Action "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4/aanleveren_opleidingseenheid"]
      [:wsa:From
        [:wsa:Address "http://www.w3.org/2005/08/addressing/anonymous?oin=0000000700099ZZ00000"]]
      [:wsa:MessageID "uuid:38aefb9e-f6f5-4b77-82f4-30db9e22c98a"]
      [:wsa:To "http://localhost/RIO/services/beheren4.0?oin=00000001800866472000"]]
    [:SOAP-ENV:Body docroot]])

(defn generate-docroot [education-specification]
  [:duo:aanleveren_opleidingseenheid_request {:xmlns:duo "http://duo.nl/schema/DUO_RIO_Beheren_OnderwijsOrganisatie_V4"}
    [:duo:identificatiecodeBedrijfsdocument "26330d25-7887-4319-aab6-752463650faf"]
    [:duo:verzendendeInstantie "mijnSchool"]
    [:duo:ontvangendeInstantie "DUO"]
    [:duo:datumTijdBedrijfsdocument "2022-03-24T12:01:42Z"]
    (generate-xml-hoopleiding education-specification)])

(defn generate-soap [education-specification]
  (-> education-specification
      (generate-docroot)
      (apply-soap)
      (clj-xml/sexp-as-element)))

(defn xml-str [education-specification]
  (-> education-specification
      (generate-docroot)
      (clj-xml/sexp-as-element)
      (clj-xml/indent-str)))

(defn soap-str [education-specification]
  (-> education-specification
      (generate-soap)
      (clj-xml/indent-str)))
