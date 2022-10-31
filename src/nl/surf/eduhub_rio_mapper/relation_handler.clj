(ns nl.surf.eduhub-rio-mapper.relation-handler
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.Relation :as-alias Relation]
            [nl.surf.eduhub-rio-mapper.RelationChild :as-alias RelationChild]
            [nl.surf.eduhub-rio-mapper.RelationParent :as-alias RelationParent]
            [nl.surf.eduhub-rio-mapper.rio.mutator]))

(s/def ::Relation/parent-opleidingseenheidcode string?)
(s/def ::Relation/child-opleidingseenheidcode string?)
(s/def ::Relation/valid-from ::common/date)

(s/def ::Relation/relation
  (s/keys :req-un [::Relation/parent-opleidingseenheidcode ::Relation/child-opleidingseenheidcode ::Relation/valid-from]
          :opt-un [::valid-to]))

(s/def ::Relation/relation-vector
  (s/and vector? (s/coll-of ::Relation/relation)))

(s/def ::Relation/relation-set
  (s/and set? (s/coll-of ::Relation/relation)))

(s/def ::Relation/relation-collection
  (s/coll-of ::Relation/relation))

(s/def ::Relation/missing ::Relation/relation-set)
(s/def ::Relation/superfluous ::Relation/relation-set)

(s/def ::Relation/relation-diff
  (s/keys :req-un [::Relation/missing ::Relation/superfluous]))

(s/def ::Relation/educationSpecificationType #(= % "program"))
(s/def ::RelationParent/educationSpecificationSubType nil?)
(s/def ::RelationChild/educationSpecificationSubType #(= % "variant"))
(s/def ::Relation/parent (s/keys :req-un [::Relation/educationSpecificationType]
                                 :opt-un [::RelationParent/educationSpecificationSubType]))
(s/def ::Relation/child (s/keys :req-un [::Relation/educationSpecificationType]
                                 :opt-un [::RelationChild/educationSpecificationSubType]))

(defn- narrow-valid-daterange
  "The relation's valid-from and valid-to is stricter than that of the parent or child.

  It starts as soon as both entities are valid, and ends as soon as either entity is no longer valid (may be nil)."
  [{:keys [parent child] :as relation}]
  (let [valid-from (last (sort (map :validFrom [parent child])))
        valid-to (first (sort (keep :validTo [parent child])))]
    (assoc relation
      :valid-from valid-from
      :valid-to valid-to)))

(defn- turn-into-relations [{:keys [parent child] :as relation}]
  {:pre  [(:rio-code parent)
          (:rio-code child)
          (:validFrom parent)
          (:validFrom child)]
   :post [(s/valid? ::Relation/relation %)]}
  (assoc (select-keys relation [:valid-from :valid-to])
    :parent-opleidingseenheidcode (:rio-code parent)
    :child-opleidingseenheidcode (:rio-code child)))

(defn- expected-relations [parent children]
  {:post [(s/valid? ::Relation/relation-vector (vec %))]}
  (->> children
       (map (fn [child] {:parent parent :child child}))
       (map narrow-valid-daterange)
       (map turn-into-relations)))

(defn relation-differences
  "Returns the diff between actual relations and expected relations."
  [main-entity rel-dir secondary-entity actual]
  {:pre [(s/valid? ::Relation/relation-collection actual)]
   :post [(s/valid? ::Relation/relation-diff %)]}
  (let [expected
           (if (= rel-dir :child)
             (when (s/valid? ::Relation/parent secondary-entity)
               (expected-relations secondary-entity [main-entity]))
             (expected-relations main-entity secondary-entity))]
    {:missing     (set/difference (set expected) (set actual))
     :superfluous (set/difference (set actual) (set expected))}))

(defn relation-mutation
  "Returns the request data needed to perform a mutation (either an insertion or a deletion)."
  [mutate-type institution-oin {:keys [parent-opleidingseenheidcode child-opleidingseenheidcode valid-from valid-to]}]
  {:post [(s/valid? ::Mutation/mutation %)]}
  (let [rio-sexp `[[:duo:opleidingsrelatie
                   [:duo:begindatum ~valid-from]
                   ~@(when (and (= mutate-type :insert)
                                (some? valid-to))
                       [[:duo:einddatum valid-to]])
                   [:duo:opleidingseenheidcode ~parent-opleidingseenheidcode]
                   [:duo:opleidingseenheidcode ~child-opleidingseenheidcode]]]]
    {:action     (case mutate-type :insert "aanleveren_opleidingsrelatie"
                                   :delete "verwijderen_opleidingsrelatie")
     :sender-oin institution-oin
     :rio-sexp   rio-sexp}))

(defn- load-relation-data [opleidingscode getter institution-oin]
  {:pre [opleidingscode]
   :post [(s/valid? ::Relation/relation-vector %)]}
  (getter institution-oin "opleidingsrelatiesBijOpleidingseenheid" opleidingscode))

(defn delete-relations [opleidingscode institution-oin mutate getter]
  {:pre [opleidingscode]}
  (doseq [rel (load-relation-data opleidingscode getter institution-oin)]
    (-> (relation-mutation :delete institution-oin rel)
        mutate)))

(defn- relation-mutations
  [eduspec {:keys [institution-oin institution-schac-home] :as _job} {:keys [getter resolver ooapi-loader]}]
  (let [add-rio-code (fn add-rio-code [entity]
                       (when-let [rio-code (-> entity :educationSpecificationId (resolver institution-oin))]
                         (assoc entity :rio-code rio-code)))
        load-eduspec (fn load-eduspec [id]
                       (let [es (ooapi-loader {::ooapi/type            "education-specification"
                                               ::ooapi/id              id
                                               :institution-schac-home institution-schac-home})]
                         (add-rio-code es)))
        eduspec (add-rio-code eduspec)
        actual (load-relation-data (:rio-code eduspec) getter institution-oin)]
    (when-let [[rel-dir entity] (case (:educationSpecificationSubType eduspec)
                                  "variant" [:child (load-eduspec (:parent eduspec))]
                                  nil       [:parent (->> (keep load-eduspec (:children eduspec))
                                                          (filter #(s/valid? ::Relation/child %)))])]
      (relation-differences eduspec rel-dir entity actual))))

(defn- mutate-relations!
  [{:keys [missing superfluous] :as diff} mutate! institution-oin]
  (let [mutator (fn [rel op] (-> (relation-mutation op institution-oin rel)
                                 mutate!))]
    (doseq [rel missing]     (mutator rel :insert))
    (doseq [rel superfluous] (mutator rel :delete)))
  diff)

(defn after-upsert
  "Calculates which relations exist in ooapi, which relations exist in RIO, and synchronizes them.

  Only relations between education-specifications are considered; specifically, relations with type program,
  one with no subtype and one with subtype variant.
  To perform synchronization, relations are added and deleted in RIO."
  [eduspec job {:keys [mutate] :as handlers}]
  {:pre [eduspec (:institution-schac-home job)]}
  (-> (relation-mutations eduspec job handlers)
      (mutate-relations! mutate (:institution-oin job))))
