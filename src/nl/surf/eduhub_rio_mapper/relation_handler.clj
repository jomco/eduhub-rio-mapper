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
  (assoc (select-keys relation [:valid-from :valid-to])
    :parent-opleidingseenheidcode (:rio-code parent)
    :child-opleidingseenheidcode (:rio-code child)))

(defn- expected-relations [parent children]
  (->> children
       (map (fn [child] {:parent parent :child child}))
       (map narrow-valid-daterange)
       (map turn-into-relations)))

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
   :post [(s/assert ::Relation/relation-vector %)]}
  (getter institution-oin "opleidingsrelatiesBijOpleidingseenheid" opleidingscode))

(defn delete-relations [opleidingscode institution-oin mutate getter]
  {:pre [opleidingscode]}
  (doseq [rel (load-relation-data opleidingscode getter institution-oin)]
    (-> (mutate-relation :delete institution-oin rel)
        mutate)))

(defn- relation-mutations
  [eduspec {:keys [institution-oin institution-schac-home] :as _job} {:keys [getter resolver ooapi-loader]}]
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
      (relation-differences eduspec rel-dir entity actual))))

(defn- mutate-relations!
  [{:keys [missing superfluous]} mutate institution-oin]
  (let [mutator (fn [rel op] (-> (mutate-relation op institution-oin rel) (mutate)))]
    (doseq [rel missing]     (mutator rel :insert))
    (doseq [rel superfluous] (mutator rel :delete))))

(defn after-upsert
  "Calculates which relations exist in ooapi, which relations exist in RIO, and synchronizes them.

  Only relations between education-specifications are considered; specifically, relations with type program,
  one with no subtype and one with subtype variant.
  To perform synchronization, relations are added and deleted in RIO."
  [eduspec job {:keys [mutate] :as handlers}]
  {:pre [eduspec (:institution-schac-home job)]}
  (-> (relation-mutations eduspec job handlers)
      (mutate-relations! mutate (:institution-oin job))))
