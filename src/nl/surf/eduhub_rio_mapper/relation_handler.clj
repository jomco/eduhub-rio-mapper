(ns nl.surf.eduhub-rio-mapper.relation-handler
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.Relation :as-alias Relation]))

(s/def ::Relation/relation
  (s/keys :req-un [::parent-opleidingseenheidcode ::child-opleidingseenheidcode ::valid-from]
          :opt-un [::valid-to]))

(s/def ::Relation/relation-vector
  (s/and vector? (s/coll-of ::Relation/relation)))

(s/def ::Relation/relation-set
  (s/and set? (s/coll-of ::Relation/relation)))

(defn- expected-relations [parent children]
  (map (fn expected-relations-map [child]
         (let [valid-from (last (sort (keep :validFrom [parent child])))
               valid-to (first (sort (keep :validTo [parent child])))]
           {:parent-opleidingseenheidcode (:rio-code parent)
            :child-opleidingseenheidcode (:rio-code child)
            :valid-from valid-from
            :valid-to valid-to}))
       children))

(defn- program-type? [eduspec] (= "program" (:educationSpecificationType eduspec)))

(defn- subtype [eduspec] (:educationSpecificationSubType eduspec))

(defn- valid-parent? [parent]
  (and (some? parent)
       (program-type? parent)
       (nil? (subtype parent))))

(defn relation-differences [main-entity rel-dir secondary-entity actual]
  {:pre [(s/assert ::Relation/relation-set actual)]}
  (let [expected
           (if (= rel-dir :child)
             (when (valid-parent? secondary-entity)
               (expected-relations secondary-entity [main-entity]))
             (expected-relations main-entity secondary-entity))]
    {:missing     (set/difference (set expected) (set actual))
     :superfluous (set/difference (set actual) (set expected))}))

(defn valid-child? [child]
  (and (program-type? child)
       (= "variant" (subtype child))))

(defn mutate-relation
  "Returns the request data needed to perform a mutation (either an insertion or a deletion)."
  [mutate-type institution-oin {:keys [parent-opleidingseenheidcode child-opleidingseenheidcode valid-from valid-to]}]
  (let [rio-sexp (if (and (= mutate-type :insert)
                          (some? valid-to))
                   [[:duo:opleidingsrelatie
                     [:duo:begindatum valid-from]
                     [:duo:einddatum valid-to]
                     [:duo:opleidingseenheidcode parent-opleidingseenheidcode]
                     [:duo:opleidingseenheidcode child-opleidingseenheidcode]]]
                   [[:duo:opleidingsrelatie
                     [:duo:begindatum valid-from]
                     [:duo:opleidingseenheidcode parent-opleidingseenheidcode]
                     [:duo:opleidingseenheidcode child-opleidingseenheidcode]]])]
    {:action     (case mutate-type :insert "aanleveren_opleidingsrelatie"
                                   :delete "verwijderen_opleidingsrelatie")
     :sender-oin institution-oin
     :rio-sexp   rio-sexp}))

(defn- load-relation-data [opleidingscode getter institution-oin]
  {:pre [opleidingscode]
   :post [(s/assert ::Relation/relation-vector %)]}
  (getter institution-oin "opleidingsrelatiesBijOpleidingseenheid" opleidingscode))

(defn delete-relations [opleidingscode institution-oin mutate getter]
  {:pre [opleidingscode]}
  (doseq [rel (load-relation-data opleidingscode getter institution-oin)]
    (-> (mutate-relation :delete institution-oin rel)
        mutate)))

(defn after-upsert
  "Calculates which relations exist in ooapi, which relations exist in RIO, and synchronizes them.

  Only relations between education-specifications are considered; specifically, relations with type program,
  one with no subtype and one with subtype variant.
  To perform synchronization, relations are added and deleted in RIO."
  [eduspec institution-oin institution-schac-home {:keys [getter resolver ooapi-loader mutate]}]
  {:pre [eduspec institution-schac-home]}
  (let [rio-code (fn [entity]
                  (-> entity :educationSpecificationId (resolver institution-oin) :code))
        load-eduspec (fn [id]
                       (let [es (ooapi-loader {::ooapi/type            "education-specification"
                                               ::ooapi/id              id
                                               :institution-schac-home institution-schac-home})]
                         (when-let [code (rio-code es)]
                           (assoc es :rio-code code))))
        actual (load-relation-data (rio-code eduspec) getter institution-oin)]
    (when-let [[rel-dir entity] (case (subtype eduspec)
                                "variant" [:child (load-eduspec (:parent eduspec))]
                                nil       [:parent (->> (keep load-eduspec (:children eduspec))
                                                        (filter valid-child?))])]
      (let [{:keys [missing superfluous]} (relation-differences eduspec rel-dir entity actual)
            mutator (fn [rel op] (-> (mutate-relation op institution-oin rel) (mutate)))]
        (doseq [rel missing]     (mutator rel :insert))
        (doseq [rel superfluous] (mutator rel :delete))))))
