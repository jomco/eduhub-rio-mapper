(ns nl.surf.eduhub-rio-mapper.ooapi.endpoints
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.data.xml :as clj-xml]
            [clojure.string :as str]
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

; TODO If opleidingseenheidcode exists in rio, add rio ID
(defn education-specification-updated [education-specification-id ooapi-bridge]
  (let [eduspec (ooapi-bridge "education-specification" education-specification-id)]
    {:action "aanleveren_opleidingseenheid"
     :rio-sexp (opl-eenh/education-specification->opleidingseenheid eduspec)}))

(defn program-updated [program-id ooapi-bridge]
  (let [program (ooapi-bridge "program" program-id)
        ;; TODO We currently don't handle more than 250 offerings per program
        offerings (:items (ooapi-bridge "program-offerings" program-id))
        eduspec (ooapi-bridge "education-specification" (:educationSpecification program))
        type (:educationSpecificationType eduspec)]
    {:action "aanleveren_aangebodenOpleiding"
     :rio-sexp (aangeboden-opl/program->aangeboden-opleiding (assoc program :offerings offerings) type)}))

(defn course-updated [course-id ooapi-bridge]
  (let [course (ooapi-bridge "course" course-id)
        ;; TODO We currently don't handle more than 250 offerings per course
        offerings (:items (ooapi-bridge "course-offerings" course-id))]
    {:action "aanleveren_aangebodenOpleiding"
     :rio-sexp (aangeboden-opl/course->aangeboden-opleiding (assoc course :offerings offerings))}))

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
