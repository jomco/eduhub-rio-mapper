(ns nl.surf.eduhub-rio-mapper.ooapi.endpoints
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as course]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offerings]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as program]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]))

(def ooapi-root-url "http://demo01.eduapi.nl/v5/")

(defn ooapi-http-bridge [root-url type id]
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

(defn ooapi-http-bridge-maker [root-url]
  (fn [type id] (ooapi-http-bridge root-url type id)))

(defn ooapi-file-bridge [type id]
  (let [path-fn (case type
                  "education-specification" #(str "education-specification-" (first (str/split % #"O")) ".json")
                  "program" #(str "program-" % ".json")
                  "course" #(str "course-" % ".json")
                  "course-offerings" #(str "course-offerings-" % ".json")
                  "program-offerings" #(str "program-offerings-" % ".json"))]
    (json/read-str (slurp (str "dev/fixtures/" (path-fn id))) :key-fn keyword)))

(def type-to-spec-mapping
  {:course                  ::course/Course
   :program                 ::program/Program
   :education-specification ::education-specification/EducationSpecificationTopLevel
   :course-offerings        ::offerings/OfferingsRequest
   :program-offerings       ::offerings/OfferingsRequest})

(def valid-ooapi-types (set (map keyword (keys type-to-spec-mapping))))

(defn- load-and-validate
  "Loads a ooapi object with given type and id from the ooapi-bridge and validates it against the spec."
  [ooapi-bridge type id]
  {:pre [(valid-ooapi-types type)]}
  (let [json (ooapi-bridge (name type) id)
        spec (type-to-spec-mapping type)
        problems (:clojure.spec.alpha/problems (s/explain-data spec json))]
    (if (nil? problems)
      {:result json}
      {:errors problems :type (name type) :id id})))

(defn circuit-breaker-reducer [a calc-fn]
  (let [r (calc-fn a)]
    (cond (reduced? r) r
          (:errors r) (reduced r)
          :else (merge a r))))

(defn education-specification-updated [education-specification-id _ ooapi-bridge rio-bridge]
  (reduce circuit-breaker-reducer {}
          [(fn [_] (load-and-validate ooapi-bridge :education-specification education-specification-id))
           (fn [h] (let [eduspec (:result h)
                         opleidingscode (:code (rio-bridge education-specification-id))
                         ooapi (if opleidingscode (assoc eduspec :rioId opleidingscode) eduspec)] ; may be nil
                     (reduced {:action   "aanleveren_opleidingseenheid"
                               :ooapi    ooapi
                               :rio-sexp (opl-eenh/education-specification->opleidingseenheid ooapi)})))]))

(def missing-rio-id-message "RIO kent momenteel geen opleidingsonderdeel met eigenOpleidingseenheidSleutel %s.\nDeze wordt automatisch aangemaakt wanneer er een update komt voor een\n education specification.")

(defn course-program-updated [id course? ooapi-bridge rio-bridge]
  (reduce circuit-breaker-reducer {}
          [(fn [_] (load-and-validate ooapi-bridge (if course? :course :program) id))
           (fn [h] {:course-program (:result h), :eduspec-id (-> h :result :educationSpecification)})
           (fn [h] (load-and-validate ooapi-bridge :education-specification (:eduspec-id h)))
           (fn [h] {:eduspec-type (-> h :result :educationSpecificationType)})
           (fn [_] (load-and-validate ooapi-bridge (if course? :course-offerings :program-offerings) id))
           (fn [h] {:offerings (-> h :result :items)})
           (fn [h]
             (let [code (:code (rio-bridge (:eduspec-id h)))] ; In this case, we ignore the error message from RIO
               (if (some? code)
                 {:rioId code}
                 {:errors (format missing-rio-id-message (:eduspec-id h))})))
           (fn [{:keys [course-program offerings eduspec-type rioId]}]
             (let [ooapi (assoc course-program :offerings offerings :educationSpecification rioId)]
               (reduced {:action   "aanleveren_aangebodenOpleiding"
                         :ooapi    ooapi
                         :rio-sexp (if course?
                                     (aangeboden-opl/course->aangeboden-opleiding ooapi)
                                     (aangeboden-opl/program->aangeboden-opleiding ooapi eduspec-type))})))]))
