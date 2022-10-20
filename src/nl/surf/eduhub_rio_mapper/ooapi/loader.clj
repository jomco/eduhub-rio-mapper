(ns nl.surf.eduhub-rio-mapper.ooapi.loader
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.errors :as errors :refer [result-> when-result]]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as course]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offerings]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as program]))

(defn- add-credentials
  [http-request {:keys [username password]}]
  ;; processed by clj-http.client middleware
  (assoc http-request :basic-auth [username password]))


;; This limit will be lifted later, to be replaced by pagination.
;;
;; See also https://trello.com/c/LtBQ8aaA/46

(def ^:private max-offerings
  "Maximum amount of course and program offerings that will be mapped."
  250)

(defn- ooapi-type->path [ooapi-type id]
  (-> ooapi-type
      (case
        "education-specification" "education-specifications/%s"
        "program" "programs/%s?returnTimelineOverrides=true"
        "course" "courses/%s?returnTimelineOverrides=true"
        "course-offerings" (str "courses/%s/offerings?pageSize=" max-offerings)
        "program-offerings" (str "programs/%s/offerings?pageSize=" max-offerings))
      (format id)))

;; We should never receive /more/ than max-offerings items, but
;; check with <= just to be sure
(defn- check-max-offerings [results url]
  (when (<= max-offerings (count (:items results)))
    (throw (ex-info (str "Hit max offerings limit for url " url)
                    {:max-offerings max-offerings
                     :url           url
                     :num-items     (count (:items results))}))))

(defn ooapi-http-loader
  [{::ooapi/keys [root-url type id] :keys [institution-schac-home gateway-credentials]}]
  {:pre [institution-schac-home]}
  (let [path (ooapi-type->path type id)
        url (str root-url path)
        headers (if institution-schac-home {"X-Route" (str "endpoint=" institution-schac-home)
                                            "Accept" "application/json; version=5"}
                                           {})
        auth-opts (if gateway-credentials (add-credentials {} gateway-credentials)
                                          {})
        {:keys [body success status]} (http-utils/send-http-request {:url url
                                                                     :headers headers
                                                                     :method :get
                                                                     :content-type :json
                                                                     :auth-opts auth-opts})]
    (when-not success
      (throw (ex-info (format "Unexpected http status %s calling ooapi with path %s" status path) {})))

    (let [results (json/read-str body :key-fn keyword)
          results (if institution-schac-home
                    ;; unwrap gateway envelop
                    (get-in results [:responses (keyword institution-schac-home)])
                    results)]

      (check-max-offerings results url)

      results)))

(defn make-ooapi-http-loader
  [root-url credentials]
  (fn [context]
    (ooapi-http-loader (assoc context
                              ::ooapi/root-url root-url
                              :gateway-credentials credentials))))

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

(defn- load-offerings
  [loader {::ooapi/keys [id type] :as request}]
  (case type
    "education-specification"
    nil

    ("course" "program")
    (result-> (loader (assoc request
                             ::ooapi/id id
                             ::ooapi/type (str type "-offerings")))
              :items)))

(defn- validating-loader
  [loader]
  (fn [{::ooapi/keys [type id] :as request}]
    (when-result [entity (loader request)
                  problems (:clojure.spec.alpha/problems (s/explain-data (type-to-spec-mapping type) entity))]
      (if problems
        (do
          (log/debug (format "Spec errors for type %s and id %s" type id))
          {:errors problems :type type :id id :ooapi entity})
        entity))))

(defn wrap-load-entities
  "Middleware for loading and validating ooapi entitites.

  Gets ooapi/type and ooapi/id from the request and fetches the given
  entity + its related offerings and its education-specification.

  The resulting entity is passed along as ::ooapi/entity
  with :offerings. The related education-specification is passed
  as ::ooapi/education-specification."
  [f ooapi-loader]
  (let [loader (validating-loader ooapi-loader)]
    (fn [{:keys [::ooapi/type] :as request}]
      (when-result [entity (loader request)

                    offerings (load-offerings loader request)
                    education-specification (if (= type "education-specification")
                                              entity
                                              (loader (assoc request
                                                             ::ooapi/type "education-specification"
                                                             ::ooapi/id (ooapi/education-specification-id entity))))]
        (f (assoc request
                  ::ooapi/entity (assoc entity :offerings offerings)
                  ::ooapi/education-specification education-specification))))))
