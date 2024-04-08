;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.rio.updated-handler
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]
            [nl.surf.eduhub-rio-mapper.rio.relation-handler :as relation-handler]
            [nl.surf.eduhub-rio-mapper.rio.rio :as rio]))

;; We have full entities in the request for upserts and then we need to
;; also fetch the education-specification from the entity if it's a
;; coarse or program.
;;
;; For deletes we don't have a full entity in the request (since it's
;; been deleted) and we only need the education-specification id if
;; the root entity is an education-specification.

(defn education-specification-id
  [{:keys [::ooapi/entity ::ooapi/type ::ooapi/id]}]
  (if (= type "education-specification")
    id
    (ooapi/education-specification-id entity)))

(defn wrap-resolver
  "Get the RIO opleidingscode and aangeboden opleiding code for the given entity.

  Inserts the codes in the request as ::rio/opleidingscode
  and ::rio/aangeboden-opleiding-code (if entity is a course or
  program)."
  [f resolver]
  (fn with-resolver [{:keys [institution-oin] ::ooapi/keys [type id] ::rio/keys [opleidingscode] :as request}]
    (f (cond-> request
         (#{"course" "program"} type)
         (assoc ::rio/aangeboden-opleiding-code
                (resolver type id institution-oin))

         true
         (assoc ::rio/opleidingscode
                (or opleidingscode (resolver "education-specification" (education-specification-id request) institution-oin)))))))

(defn update-mutation
  "Returned object conforms to ::Mutation/mutation-response."
  [{:keys [institution-oin args]
    ::ooapi/keys [id entity type education-specification]
    ::rio/keys [opleidingscode aangeboden-opleiding-code]}]
  {:post [(s/valid? ::Mutation/mutation-response %)]}
  (assert institution-oin)
  (if (and (not (#{"education-specification" "relation"} type))
           (not opleidingscode))
    ;; If we're not inserting a new education-specification or a
    ;; relation we need a rio code (from an earlier inserted
    ;; education-specification).
    (let [id (ooapi/education-specification-id entity)]
      (throw (ex-info (str "Education specification " id " not yet known by RIO updating " type)
                      {:entity     entity
                       :retryable? false})))
    (let [entity (cond-> entity
                   (and opleidingscode
                        (= "education-specification" type))
                   (assoc :rioCode opleidingscode)

                   (and aangeboden-opleiding-code
                        (#{"course" "program"} type))
                   (assoc :rioCode aangeboden-opleiding-code))]
      (case type
        "education-specification"
        {:action     "aanleveren_opleidingseenheid"
         :ooapi      entity
         :sender-oin institution-oin
         :rio-sexp   [(opl-eenh/education-specification->opleidingseenheid entity)]}

        "course"
        {:action     "aanleveren_aangebodenOpleiding"
         :ooapi      entity
         :sender-oin institution-oin
         :rio-sexp   [(aangeboden-opl/->aangeboden-opleiding entity :course opleidingscode "course")]}

        "program"
        {:action     "aanleveren_aangebodenOpleiding"
         :ooapi      entity
         :sender-oin institution-oin
         :rio-sexp   [(aangeboden-opl/->aangeboden-opleiding entity :program opleidingscode (:educationSpecificationType education-specification))]}

        "relation"
        (let [[object-code valid-from valid-to] args]
          (relation-handler/relation-mutation :insert institution-oin
                                              {:opleidingseenheidcodes #{id object-code}
                                               :valid-from             valid-from
                                               :valid-to               valid-to}))))))

(defn deletion-mutation
  "Returned object conforms to ::Mutation/mutation-response."
  [{:keys [::rio/opleidingscode ::rio/aangeboden-opleiding-code ::ooapi/type ::ooapi/id institution-oin args]}]
  {:post [(s/valid? ::Mutation/mutation-response %)]}
  (assert institution-oin)
  (case type
    "education-specification"
    (if opleidingscode
      {:action     "verwijderen_opleidingseenheid"
       :sender-oin institution-oin
       :rio-sexp   [[:duo:opleidingseenheidcode opleidingscode]]}
      (throw (ex-info "Unable to delete 'opleidingseenheid' without 'opleidingscode'"
                      {:education-specification-id id,
                       :retryable?                 false})))

    ("course" "program")
    (if aangeboden-opleiding-code
      {:action     "verwijderen_aangebodenOpleiding"
       :sender-oin institution-oin
       :rio-sexp   [[:duo:aangebodenOpleidingCode aangeboden-opleiding-code]]}
      (throw (ex-info "Unable to delete 'aangebodenopleiding' without 'aangebodenopleidingscode'"
                      {(keyword (str type "-id"))  id,
                       :retryable?                 false})))

    ;; Only called explicitly from the command line.
    "relation"
    (let [[other-code valid-from] args]
      (relation-handler/relation-mutation :delete institution-oin
                                          {:opleidingseenheidcodes #{id other-code}
                                           :valid-from             valid-from}))))
