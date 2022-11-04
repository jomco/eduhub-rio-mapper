(ns nl.surf.eduhub-rio-mapper.relation-handler
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.Relation :as-alias Relation]
            [nl.surf.eduhub-rio-mapper.RelationChild :as-alias RelationChild]
            [nl.surf.eduhub-rio-mapper.RelationParent :as-alias RelationParent]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]))

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
  {:pre  [(::rio/opleidingscode parent)
          (::rio/opleidingscode child)
          (:validFrom parent)
          (:validFrom child)]
   :post [(s/valid? ::Relation/relation %)]}
  (assoc (select-keys relation [:valid-from :valid-to])
         :parent-opleidingseenheidcode (::rio/opleidingscode parent)
         :child-opleidingseenheidcode (::rio/opleidingscode child)))

(defn- expected-relations [parent children]
  {:post [(s/valid? ::Relation/relation-vector (vec %))]}
  (->> children
       (map (fn [child] {:parent parent :child child}))
       (map narrow-valid-daterange)
       (map turn-into-relations)))

(defn relation-differences
  "Returns the diff between actual relations and expected relations."
  [main-entity rel-dir secondary-entity actual]
  {:pre [(s/valid? (s/nilable ::Relation/relation-collection) actual)]
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
  {:pre [institution-oin]
   :post [(s/valid? ::Mutation/mutation-response %)]}
  (let [rio-sexp (case mutate-type
                   :insert `[[:duo:opleidingsrelatie
                              [:duo:begindatum ~valid-from]
                              ~@(when valid-to
                                  [[:duo:einddatum valid-to]])
                              [:duo:opleidingseenheidcode ~parent-opleidingseenheidcode]
                              [:duo:opleidingseenheidcode ~child-opleidingseenheidcode]]]
                   :delete [[:duo:opleidingseenheidcode parent-opleidingseenheidcode]
                            [:duo:opleidingseenheidcode child-opleidingseenheidcode]
                            [:duo:begindatum valid-from]])]
    {:action     (case mutate-type :insert "aanleveren_opleidingsrelatie"
                                   :delete "verwijderen_opleidingsrelatie")
     :sender-oin institution-oin
     :rio-sexp   rio-sexp}))

(defn- load-relation-data [getter opleidingscode institution-oin]
  {:pre [(s/valid? ::rio/opleidingscode opleidingscode)]
   :post [(s/valid? (s/nilable ::Relation/relation-vector) %)]}
  (getter {:institution-oin       institution-oin
           ::rio/type             "opleidingsrelatiesBijOpleidingseenheid"
           ::rio/opleidingscode   opleidingscode}))

(defn delete-relations [opleidingscode type institution-oin {:keys [rio-config getter]}]
  {:pre [(s/valid? ::rio/opleidingscode opleidingscode)]}
  (when (= type "education-specification")
    (doseq [rel (load-relation-data getter opleidingscode institution-oin)]
      (-> (relation-mutation :delete institution-oin rel)
          (mutator/mutate! rio-config)))))

(defn- relation-mutations
  [eduspec {:keys [institution-oin institution-schac-home] :as _job} {:keys [getter resolver ooapi-loader]}]
  (let [add-rio-code (fn add-rio-code [entity]
                       (when entity
                         (if (::rio/opleidingscode entity)
                           entity
                           (when-let [rio-code (resolver "education-specification" (:educationSpecificationId entity) institution-oin)]
                             (assoc entity ::rio/opleidingscode rio-code)))))
        load-eduspec (fn load-eduspec [id]
                       (let [es (ooapi-loader {::ooapi/type            "education-specification"
                                               ::ooapi/id              id
                                               :institution-schac-home institution-schac-home})]
                         (add-rio-code es)))
        eduspec (add-rio-code eduspec)]
    (when eduspec
      (let [actual (load-relation-data getter (::rio/opleidingscode eduspec) institution-oin)
            rio-consumer (some->> (:consumers eduspec) (filter #(= (:consumerKey %) "rio")) first)]
        (when-let [[rel-dir entity] (case (:educationSpecificationSubType rio-consumer)
                                      "variant" [:child (load-eduspec (:parent eduspec))]
                                      nil       [:parent (->> (keep load-eduspec (:children eduspec))
                                                              (filter #(s/valid? ::Relation/child %)))]
                                      nil)]
          (relation-differences eduspec rel-dir entity actual))))))

(defn- mutate-relations!
  [{:keys [missing superfluous] :as diff} rio-config institution-oin]
  {:pre [institution-oin (:recipient-oin rio-config)]}
  (let [mutator (fn [rel op] (-> (relation-mutation op institution-oin rel)
                                 (mutator/mutate! rio-config)))]
    (doseq [rel missing]     (mutator rel :insert))
    (doseq [rel superfluous] (mutator rel :delete)))
  diff)

(defn after-upsert
  "Calculates which relations exist in ooapi, which relations exist in RIO, and synchronizes them.

  Only relations between education-specifications are considered; specifically, relations with type program,
  one with no subtype and one with subtype variant.
  To perform synchronization, relations are added and deleted in RIO."
  [eduspec {:keys [institution-oin] :as job} {:keys [rio-config] :as handlers}]
  {:pre [eduspec (:institution-schac-home job) institution-oin (:recipient-oin rio-config)]}
  (-> (relation-mutations eduspec job handlers)
      (mutate-relations! rio-config institution-oin)))
