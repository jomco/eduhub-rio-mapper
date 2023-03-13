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

(ns nl.surf.eduhub-rio-mapper.ooapi.loader
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as course]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offerings]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as program]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import [java.net URI]))

;; This limit will be lifted later, to be replaced by pagination.
;;
;; See also https://trello.com/c/LtBQ8aaA/46

(def ^:private max-offerings
  "Maximum amount of course and program offerings that will be mapped."
  250)

(defn- ooapi-type->path [ooapi-type id]
  (-> ooapi-type
      (case
        "education-specification" "education-specifications/%s?returnTimelineOverrides=true"
        "program" "programs/%s?returnTimelineOverrides=true"
        "course" "courses/%s?returnTimelineOverrides=true"
        "course-offerings" (str "courses/%s/offerings?pageSize=" max-offerings "&consumer=rio")
        "program-offerings" (str "programs/%s/offerings?pageSize=" max-offerings "&consumer=rio"))
      (format id)))

;; We should never receive /more/ than max-offerings items, but
;; check with <= just to be sure
(defn- guard-max-offerings
  "Guard against exceeding max-offerings items in result.
  Throws an exception when amount of items in result exceeds
  max-offerings (which should never happen) or is the same as
  max-offerings (in which case it probably has more than that)."
  [result context]
  (when (<= max-offerings (count (:items result)))
    (throw (ex-info (str "Hit max offerings limit: "
                         (count (:items result)) " >= " max-offerings)
                    (assoc context
                           :max-offerings max-offerings
                           :num-items (count (:items result))))))
  result)

(s/def ::ooapi/root-url #(instance? URI %))
(s/def ::ooapi/type string?)
(s/def ::ooapi/id string?)
(s/def ::ooapi/institution-schac-home string?)
(s/def ::ooapi/gateway-credentials (s/keys :req-un []))
(s/def ::ooapi/request (s/keys :req [::ooapi/root-url ::ooapi/type]
                               :req-un [::ooapi/institution-schac-home ::ooapi/gateway-credentials]
                               :opt [::ooapi/id ::rio/opleidingscode]))

(defn ooapi-http-loader
  [{::ooapi/keys [root-url type id]
    :keys [institution-schac-home gateway-credentials connection-timeout]
    :as ooapi-request}]
  {:pre [(s/valid? ::ooapi/request ooapi-request)]}
  (let [path    (ooapi-type->path type id)
        request (merge {:url                (str root-url path)
                        :content-type       :json
                        :method             :get
                        :connection-timeout connection-timeout
                        :headers            {"X-Route" (str "endpoint=" institution-schac-home)
                                             "Accept"  "application/json; version=5"}}
                       (when-let [{:keys [username password]} gateway-credentials]
                         {:basic-auth [username password]}))]
    (-> request
        (http-utils/send-http-request)
        :body
        (json/read-str :key-fn keyword)
        (get-in [:responses (keyword institution-schac-home)])
        (guard-max-offerings {:path path}))))

;; Returns function that takes context with the following keys:
;; ::ooapi/root-url, ::ooapi/id, ::ooapi/type, :gateway-credentials, institution-schac-home
(defn make-ooapi-http-loader
  [root-url credentials rio-config]
  (fn wrapped-ooapi-http-loader [context]
    (ooapi-http-loader (assoc context
                              ::ooapi/root-url root-url
                              :gateway-credentials credentials
                              :connection-timeout (:connection-timeout-millis rio-config)))))

(defn ooapi-file-loader
  [{::ooapi/keys [type id]}]
  (let [path (str "dev/fixtures/" type "-" id ".json")]
    (json/read-str (slurp path) :key-fn keyword)))

(def type-to-spec-mapping
  {"course"                  ::course/Course
   "program"                 ::program/Program
   "education-specification" ::education-specification/EducationSpecificationTopLevel
   "course-offerings"        ::offerings/OfferingsRequest
   "program-offerings"       ::offerings/OfferingsRequest})

(defn load-offerings
  [loader {::ooapi/keys [id type] :as request}]
  (case type
    "education-specification"
    nil

    ("course" "program")
    (-> request
        (assoc ::ooapi/id id
               ::ooapi/type (str type "-offerings"))
        (loader)
        :items)))

(defn- guard-ooapi-spec [entity {::ooapi/keys [type]}]
  (let [spec (type-to-spec-mapping type)]
    (when-not (s/valid? spec entity)
      (throw (ex-info (str "Entity fails spec: " (s/explain-str spec entity))
                      {:entity     entity
                       ;; retrying a failing spec won't help
                       :retryable? false}))))
  entity)

(defn validating-loader
  [loader]
  (fn wrapped-validating-loader [request]
    (-> request
        (loader)
        (guard-ooapi-spec request))))

(defn load-entities
  "Loads ooapi entity, including associated offerings and education specification, if applicable."
  [loader {::ooapi/keys [type] :as request}]
  (let [entity                  (loader request)
        offerings               (load-offerings loader request)
        education-specification (if (= type "education-specification")
                                  entity
                                  (-> request
                                      (assoc ::ooapi/type "education-specification"
                                             ::ooapi/id (ooapi/education-specification-id entity))
                                      (loader)))]
    (assoc request
      ::ooapi/entity (assoc entity :offerings offerings)
      ::ooapi/education-specification education-specification)))

(defn wrap-load-entities
  "Middleware for loading and validating ooapi entitites.

  Gets ooapi/type and ooapi/id from the request and fetches the given
  entity + its related offerings and its education-specification.

  The resulting entity is passed along as ::ooapi/entity
  with :offerings. The related education-specification is passed
  as ::ooapi/education-specification."
  [f ooapi-loader]
  (let [loader (validating-loader ooapi-loader)]
    (fn wrapped-load-entities [{:keys [::ooapi/type] :as request}]
      (if (= "relation" type)
        (f request)
        (->> request (load-entities loader) (f))))))
