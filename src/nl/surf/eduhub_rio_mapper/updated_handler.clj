(ns nl.surf.eduhub-rio-mapper.updated-handler
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.errors :as errors :refer [result-> when-result]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as course]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offerings]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as program]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]))

(def ooapi-root-url "http://demo01.eduapi.nl/v5/")

(defn ooapi-http-bridge
  [{::ooapi/keys [root-url type id]}]
  (let [[path page-size] (case type
               "education-specification" ["education-specifications/%s" nil]
               "program" ["programs/%s?returnTimelineOverrides=true" nil]
               "course" ["courses/%s?returnTimelineOverrides=true"]
               "course-offerings" ["courses/%s/offerings" "?pageSize=250"]
               "program-offerings" ["programs/%s/offerings" "?pageSize=250"])
        url (str root-url (format path id) page-size)
        {:keys [body status]} (http/get url)]
    (log/debug (format "GET %s %s" url status))
    (let [results (json/read-str body :key-fn keyword)]
      (when (and page-size (= 250 (count (:items results))))
        (log/warn (format "Hit pageSize limit for url %s" url)))
      results)))

(defn ooapi-http-bridge-maker
  [root-url]
  (fn [context]
    (ooapi-http-bridge (assoc context ::ooapi/root-url root-url))))

(defn ooapi-file-bridge
  [{::ooapi/keys [type id]}]
  (let [path (str "dev/fixtures/" type "-" id ".json")]
    (json/read-str (slurp path) :key-fn keyword)))

(def type-to-spec-mapping
  {"course"                  ::course/Course
   "program"                 ::program/Program
   "education-specification" ::education-specification/EducationSpecificationTopLevel
   "course-offerings"        ::offerings/OfferingsRequest
   "program-offerings"       ::offerings/OfferingsRequest})

(defn- load-offerings
  [loader {::ooapi/keys [id type]}]
  (case type
    "education-specification"
    nil

    ("course" "program")
    (result-> (loader {::ooapi/id id
                       ::ooapi/type (str type "-offerings")})
              :items)))

(defn- validating-loader
  [loader]
  (fn [{::ooapi/keys [type id] :as request}]
    (let [spec (type-to-spec-mapping type)
          entity (loader request)
          problems (:clojure.spec.alpha/problems (s/explain-data spec entity))]
      (if problems
        {:errors problems :type type :id id :ooapi entity}
        entity))))

(s/fdef education-specification-id
  :args (s/cat :entity ::ooapi/entity)
  :ret ::ooapi/id)

(defn- education-specification-id
  "Return the education specification id for the given ooapi entity.

  Takes an EducationSpecification or a Course or a Program"
  [entity]
  (or (:educationSpecification entity)
      (:educationSpecificationId entity)))

(defn wrap-load-entities
  "Middleware for loading and validating ooapi entitites.

  Gets ooapi/type and ooapi/id from the request and fetches the given
  entity + its related offerings and its education-specification.

  The resulting entity is passed along as ::ooapi/entity
  with :offerings. The related education-specification is passed
  as ::ooapi/education-specification."
  [f ooapi-bridge]
  (let [loader (validating-loader ooapi-bridge)]
    (fn [{:keys [::ooapi/type] :as request}]
      (when-result [entity (loader request)

                    offerings (load-offerings loader request)
                    education-specification (if (= type "education-specification")
                                              entity
                                              (loader {::ooapi/type "education-specification"
                                                       ::ooapi/id (education-specification-id entity)}))]
        (f (assoc request
                  ::ooapi/entity (assoc entity :offerings offerings)
                  ::ooapi/education-specification education-specification))))))

(defn wrap-resolver
  "Get the RIO opleidingscode for the given entity.

  Inserts the code in the request as ::rio/opleidingscode."
  [f resolver]
  (fn [{:keys [::ooapi/entity] :as request}]
    (f (assoc request
              ::rio/opleidingscode (:code (resolver (education-specification-id entity)))))))

(def missing-rio-id-message
  "RIO kent momenteel geen opleidingsonderdeel met eigenOpleidingseenheidSleutel %s.
Deze wordt automatisch aangemaakt wanneer er een update komt voor een
education specification.")

(defn updated-handler
  "Returns a RIO call or errors."
  [{:keys [::ooapi/entity ::rio/opleidingscode ::ooapi/type
           ::ooapi/education-specification]}]
  (if (and (not= "education-specification" type)
           (not opleidingscode))
    ;; If we're not inserting a new education-specification we need a
    ;; rio code (from an earlier inserted education-specification).
    {:errors (format missing-rio-id-message (education-specification-id entity))}
    (let [entity (cond-> entity
                   opleidingscode
                   (assoc :rioId opleidingscode))]
      (case type
        "education-specification"
        {:action "aanleveren_opleidingseenheid"
         :ooapi entity
         :rio-sexp (opl-eenh/education-specification->opleidingseenheid entity)}

        "course"
        {:action "aanleveren_aangebodenOpleiding"
         :ooapi entity
         :rio-sexp (aangeboden-opl/course->aangeboden-opleiding entity opleidingscode)}

        "program"
        {:action "aanleveren_aangebodenOpleiding"
         :ooapi entity
         :rio-sexp (aangeboden-opl/program->aangeboden-opleiding entity (:educationSpecificationType education-specification) opleidingscode)}))))
