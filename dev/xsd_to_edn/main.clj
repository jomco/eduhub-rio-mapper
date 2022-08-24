(ns xsd-to-edn.main
  (:require [clojure.data.xml :as clj-xml]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def interesting-types ["HoOnderwijseenheid" "ParticuliereOpleidingPeriode" "AangebodenParticuliereOpleidingPeriode"
                        "ParticuliereOpleiding" "AangebodenHOOpleidingCohort" "HoOnderwijseenheidPeriode"
                        "HoOnderwijseenhedencluster" "AangebodenHOOpleidingsonderdeelCohort"
                        "AangebodenHOOpleidingPeriode" "AangebodenParticuliereOpleiding" "AangebodenHOOpleidingsonderdeel"
                        "HoOnderwijseenhedenclusterPeriode" "AangebodenHOOpleidingsonderdeelPeriode" "HoOpleiding"
                        "AangebodenParticuliereOpleidingCohort" "HoOpleidingPeriode" "AangebodenHOOpleiding"])

(defn parse [{:keys [tag attrs content]}]
  (if (= (name tag) "element")
    (if (= "Kenmerk" (:type attrs))
      {:kenmerklijst true}
      (if (and (= "0" (:maxOccurs attrs))
               (some? (:ref attrs)))
        nil
        (select-keys attrs [:name :type :ref])))
    (let [kids (vec (keep parse (filter #(not (string? %)) content)))]
      (when (or (not (empty? attrs)) (not (empty? kids)))
        [(name tag) attrs (not-empty kids)]))))

(defn complex-content [cc]
  (when (and (= (count cc) 1)
             (= "complexContent" (-> cc first first)))
    (-> cc first last first rest)))

(defn resolve-base [entities]
  (fn [[k [a cs] :as all]]
    (if (nil? (:base a))
      all
      (let [[{:keys [base]} super-children] (entities (:base a))]
        (if (nil? base) [k [(dissoc a :base) (into (vec cs) super-children)]] all)))))

(defn map-hash [func coll] (into {} (map func coll)))

(defn map-and-resolve-base [coll]
  (map-hash (resolve-base coll) coll))

(defn merge-kenmerken [props props-kenmerk]
  (reduce (fn [acc pr]
            (if (:kenmerklijst pr)
              (into acc (map #(assoc % :kenmerk true) props-kenmerk))
              (conj acc pr))) [] props))

(defn resolve-refs [attrs name-to-type]
  (if (:ref attrs) (assoc attrs :type (name-to-type (:ref attrs)))
                   attrs))

(defn -main [& args]
  (let [f (slurp "resources/DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd")
        d (clj-xml/parse-str (subs f 1))
        p (last (parse d))
        name-to-type (reduce
                       (fn [h {:keys [attrs]}] (assoc h (:name attrs) (:type attrs)))
                       {}
                       (filter #(and (not (string? %))
                                     (= "element" (name (:tag %))))
                               (:content d)))
        ct (map rest (filter #(= "complexType" (first %)) p))
        hm (zipmap (map #(:name (first %)) ct)
                   (map (fn [[a c]] [(dissoc a :name) c]) ct))
        entities (into {}
                       (filter (fn [[k v]]
                                 (not (or (str/ends-with? k "_request")
                                          (str/ends-with? k "_response"))))
                               hm))
        result (->> entities
                    (map-hash (fn [[k [a children :as all]]] [k (if-let [[attr cc] (complex-content children)] [(merge a attr) cc] all)]))
                    (map-hash (fn [[k [a cs] :as all]]
                                (if (and (= 1 (count cs))
                                         (= "sequence" (first (first cs)))
                                         (empty? (first (rest (first cs)))))
                                  [k [a (last (first cs))]]
                                  all)))
                    (map-and-resolve-base)
                    (map-and-resolve-base)
                    (map-and-resolve-base)
                    (map-and-resolve-base)
                    (map-hash (fn [[k v]] [k (vec (filter seq (last v)))]))
                    (map-hash (fn [[k v]] [k (mapv #(resolve-refs % name-to-type) v)])))
        with-kenmerken (map-hash (fn [[k v]]
                                   [k (merge-kenmerken v (result (str "Kenmerkwaardenbereik_" k)))])
                                 result)]
    (pprint/pprint (select-keys with-kenmerken interesting-types))))
