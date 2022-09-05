(ns nl.surf.eduhub-rio-mapper.rio
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [re-spec]])
  (:import [java.io PushbackReader]))

(s/def ::OpleidingsEeenheidID-v01 (re-spec #"\d{4}O\d{4}"))

(def specifications (edn/read (PushbackReader. (io/reader (io/resource "ooapi-mappings.edn")))))
(def xsd-beheren (edn/read (PushbackReader. (io/reader (io/resource "beheren-schema.edn")))))

(defn ooapi-mapping? [name]
  (boolean (get-in specifications [:mappings name])))

(defn ooapi-mapping [name key]
  {:pre [(string? name)]}
  (when key (get-in specifications [:mappings name key])))

;; Helpers

(defn level-sector-mapping
  "Map level and sector to RIO `niveau`.

  Returns nil on invalid level+sector mapping."
  [level sector]
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
        "secondary vocational education 4" "MBO-4"
        nil)

      "higher professional education"
      (case level
        "associate degree" "HBO-AD"
        "bachelor" "HBO-BA"
        "master" "HBO-MA"
        "doctoral" "HBO-PM"
        "undivided" "HBO-O"
        nil)

      "university education"
      (case level
        "bachelor" "WO-BA"
        "master" "WO-MA"
        "doctoral" "WO-PM"
        "undivided" "WO-O"
        nil)
      nil)))

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

;;; XML generation

(defn name->type [nm]
  {:pre [(some? nm)]}
  (str (str/upper-case (subs nm 0 1)) (subs nm 1)))

(defn converter [parent-subtype child-abstract-type]
  (if (= child-abstract-type "AangebodenOpleidingCohort")
    {:child-type (str parent-subtype "Cohort"), :key :cohorten}
    {:child-type (str parent-subtype "Periode"), :key :periodes}))

(defn duoize [naam]
  (keyword (str "duo:" (if (keyword? naam) (name naam) naam))))

(def attr-name->kenmerk-type-mapping
  {"buitenlandsePartner" :string
   "deelnemersplaatsen" :number
   "categorie" :enum
   "deficientie" :enum
   "eigenNaamKort" :string
   "eigenAangebodenOpleidingSleutel" :string
   "eigenOpleidingseenheidSleutel" :string
   "eisenWerkzaamheden" :enum
   "internationaleNaamDuits" :string
   "opleidingsvorm" :enum
   "propedeutischeFase" :enum
   "samenwerkendeOnderwijsaanbiedercode" :string
   "soort" :enum
   "studiekeuzecheck" :enum
   "toestemmingDeelnameSTAP" :enum
   "versneldTraject" :enum
   "voertaal" :enum
   "vorm" :enum
   "website" :string})

(defn attr-name->kenmerk-type [attr-name]
  (if-let [type (attr-name->kenmerk-type-mapping attr-name)]
    type
    (do
      (log/warn (format "Missing type for kenmerk (%s), assuming it's :enum" attr-name))
      :enum)))

(defn process-attribute [attr-name attr-value kenmerk]
  (condp apply [attr-value]
    vector?
    (vec (mapcat (fn [x] (process-attribute attr-name x kenmerk)) attr-value))

    map?
    [(into [(duoize attr-name)]
           (mapv (fn [[key value]] [(duoize key) value]) attr-value))]

    [(if kenmerk
       (kenmerken attr-name (attr-name->kenmerk-type attr-name) attr-value)
       [(duoize attr-name) attr-value])]))

(defn ->xml [rio-obj object-name]
  (let [schema (xsd-beheren (name->type object-name))
        process-attributes (fn [acc kenmerk attr-name]
                            (into acc (when-let [attr-value (rio-obj (keyword attr-name))]
                                        (process-attribute attr-name attr-value kenmerk))))
        process-children (fn [acc type]
                           (let [{:keys [child-type key]} (converter object-name type)]
                             (into acc (mapv (fn [cohort] (->xml cohort child-type))
                                             (rio-obj key)))))]
    (into [(duoize object-name)]
          (->> schema
               (reduce (fn [acc {:keys [choice] :as item}]
                         (if (nil? choice)
                           (conj acc item)
                           (into acc choice)))
                       [])
               (reduce (fn [acc {:keys [kenmerk name ref type]}]
                         (if (nil? ref)
                           (process-attributes acc kenmerk name)
                           (process-children acc type)))
                       [])
               (vec)))))
