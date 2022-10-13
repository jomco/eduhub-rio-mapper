(ns nl.surf.eduhub-rio-mapper.relation-handler
  (:require [clojure.set :as set]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]))

(defn- expected-relations [parent children]
  (map (fn [child]
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
       (and (program-type? parent)
            (nil? (subtype parent)))))

(defn relation-differences [main-entity rel-dir secondary-entity actual]
  {:pre [(vector? actual)
         (every? map? actual)
         (every? #(every? % [:parent-opleidingseenheidcode :child-opleidingseenheidcode :valid-from]) actual)]}
  (let [expected
           (if (= rel-dir :child)
             (if (valid-parent? secondary-entity)
               (expected-relations secondary-entity [main-entity])
               [])
             (expected-relations main-entity secondary-entity))]
    {:missing     (vec (set/difference (set expected) (set actual)))
     :superfluous (vec (set/difference (set actual) (set expected)))}))

(defn valid-child? [child]
  (and (program-type? child)
       (= "variant" (subtype child))
       (:rio-code child)))

(defn mutate-relation
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
   :post [(vector? %)
          (every? map? %)
          (every? (fn [m] (every? m [:parent-opleidingseenheidcode :child-opleidingseenheidcode :valid-from])) %)]}
  (getter institution-oin "opleidingsrelatiesBijOpleidingseenheid" opleidingscode))

(defn delete-relations [opleidingscode institution-oin mutate getter]
  {:pre [opleidingscode]}
  (doseq [rel (load-relation-data opleidingscode getter institution-oin)]
    (-> (mutate-relation :delete institution-oin rel)
        mutate)))

(defn after-upsert
  [eduspec institution-oin institution-schac-home {:keys [getter resolver ooapi-loader mutate]}]
  {:pre [eduspec institution-schac-home]}
  (let [rio-code (fn [entity]
                  (-> entity :educationSpecificationId (resolver institution-oin) :code))
        load-parent (fn load-parent [child] (let [parent (ooapi-loader {::ooapi/type            "education-specification"
                                                                        ::ooapi/id              (:parent child)
                                                                        :institution-schac-home institution-schac-home})
                                                  code (rio-code parent)]
                                              (when code (assoc parent :rio-code "123O321"))))
        load-children (fn load-children [parent]
                        (->> (:children parent)
                             (mapv #(let [child (ooapi-loader {::ooapi/type            "education-specification"
                                                               ::ooapi/id              %
                                                               :institution-schac-home institution-schac-home})]
                                      (assoc child :rio-code (rio-code child))))
                             (filter valid-child?)))
        actual (load-relation-data (rio-code eduspec) getter institution-oin)]
    (if-let [[rel-dir entity] (case (subtype eduspec)
                                "variant" [:child (load-parent eduspec)]
                                nil       [:parent (load-children eduspec)])]
      (let [{:keys [missing-relations superfluous-relations]} (relation-differences eduspec rel-dir entity actual)]
        (doseq [rel missing-relations]
          (-> (mutate-relation :insert institution-oin rel) (mutate)))
        (doseq [rel superfluous-relations]
          (-> (mutate-relation :delete institution-oin rel) (mutate))))
      [])))
