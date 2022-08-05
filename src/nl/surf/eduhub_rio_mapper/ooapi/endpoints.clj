(ns nl.surf.eduhub-rio-mapper.ooapi.endpoints
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.data.xml :as clj-xml]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.ooapi.course :as course]
            [nl.surf.eduhub-rio-mapper.ooapi.education-specification :as education-specification]
            [nl.surf.eduhub-rio-mapper.ooapi.offerings :as offerings]
            [nl.surf.eduhub-rio-mapper.ooapi.program :as program]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opl-eenh]))

(def ooapi-root-url "http://demo01.eduapi.nl/v5/")

(defn ooapi-get [path]
  (let [response (http/get (str ooapi-root-url path))]
    (json/read-str (:body response) :key-fn keyword)))

(defn ooapi-http-bridge [root-url type id]
  (let [path (case type
               "education-specification" "/education-specifications/%s"
               "program" "/programs/%s?returnTimelineOverrides=true"
               "course" "/courses/%s?returnTimelineOverrides=true"
               "course-offerings" "courses/%s/offerings?pageSize=250"
               "program-offerings" "programs/%s/offerings?pageSize=250")
        url (str root-url (format path id))
        _ (println url)
        response (http/get url)]
    (json/read-str (:body response) :key-fn keyword)))

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
  {"course" ::course/Course
   "program" ::program/Program
   "education-specification" ::education-specification/EducationSpecificationTopLevel
   "course-offerings" ::offerings/OfferingsRequest
   "program-offerings" ::offerings/OfferingsRequest})

(defn load-and-validate [ooapi-bridge type id]
  (let [json (ooapi-bridge type id)
        spec (type-to-spec-mapping type)]
    (when (nil? spec) (throw (RuntimeException. (str "Unexpected type " type))))
    (let [problems (:clojure.spec.alpha/problems (s/explain-data spec json))]
      (if (nil? problems)
        {:result json}
        {:errors problems :type type :id id}))))

(defn updated-reducer [a calc-fn]
  (let [r (calc-fn a)]
    (cond (reduced? r) r
          (:errors r) (reduced r)
          :else (merge a r))))

; TODO If opleidingseenheidcode exists in rio, add rio ID
(defn education-specification-updated [education-specification-id ooapi-bridge]
  (reduce updated-reducer {}
          [(fn [_] (load-and-validate ooapi-bridge "education-specification" education-specification-id))
           #(reduced {:action "aanleveren_opleidingseenheid"
                      :rio-sexp (opl-eenh/education-specification->opleidingseenheid (:result %))})]))

(defn program-updated [program-id ooapi-bridge]
  (reduce updated-reducer {}
          [(fn [_] (load-and-validate ooapi-bridge "program" program-id))
           (fn [h] {:program (:result h)})
           (fn [h] (load-and-validate ooapi-bridge "education-specification" (:educationSpecification (:program h))))
           (fn [h] {:eduspec-type (get-in h [:result :educationSpecificationType])})
           (fn [_] (load-and-validate ooapi-bridge "program-offerings" program-id))
           (fn [h] {:offerings (get-in h [:result :items])})
           (fn [{:keys [program offerings eduspec-type]}]
             (reduced {:action   "aanleveren_aangebodenOpleiding"
                       :rio-sexp (aangeboden-opl/program->aangeboden-opleiding
                                   (assoc program :offerings offerings)
                                   eduspec-type)}))]))


(defn course-updated [course-id ooapi-bridge]
  (reduce updated-reducer {}
          [(fn [_] (load-and-validate ooapi-bridge "course" course-id))
           (fn [h] {:course (:result h)})
           (fn [_] (load-and-validate ooapi-bridge "course-offerings" course-id))
           (fn [h] {:offerings (get-in h [:result :items])})
           (fn [h] (reduced {:action "aanleveren_aangebodenOpleiding"
                             :rio-sexp (aangeboden-opl/course->aangeboden-opleiding
                                         (assoc (:course h) :offerings (:offerings h)))}))]))

(defn- dom-reducer [element tagname] (first (filter #(= tagname (:tag %)) (:content element))))

(defn get-in-xml
  "Walks through the DOM-tree starting with element, choosing the first element with matching qualified name."
  [current-element tag-names]
  (reduce dom-reducer current-element (map keyword tag-names)))

(defn parse-response [xml action]
  (let [root (clj-xml/parse-str xml)
        xml-response (get-in-xml root ["Body" (str action "_response")])
        goedgekeurd (-> (get-in-xml xml-response ["requestGoedgekeurd"]) :content first)
        code (-> (get-in-xml xml-response ["opleidingseenheidcode"]) :content first)]
    {:goedgekeurd goedgekeurd :code code}))
