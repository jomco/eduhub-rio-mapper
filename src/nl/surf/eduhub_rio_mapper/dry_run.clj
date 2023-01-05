(ns nl.surf.eduhub-rio-mapper.dry-run
  (:require [clojure.data.xml :as clj-xml]
            [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opleiding]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(def aangeboden-opleiding-namen (->> aangeboden-opleiding/education-specification-type-mapping
                                     vals
                                     (map keyword)
                                     set))
(def cohortnamen (->> aangeboden-opleiding-namen
                      (map name)
                      (map #(str % "Cohort"))
                      (map keyword)
                      set))
(def opvragen-aangeboden-opleiding-soap-action (str "opvragen_" rio.loader/aangeboden-opleiding))
(def opvragen-aangeboden-opleiding-response-tagname (str "ns2:" opvragen-aangeboden-opleiding-soap-action "_response"))

(defn find-aangebodenopleiding
  [id
   institution-oin
   {:keys [read-url credentials recipient-oin] :as _config}]
  {:pre [institution-oin recipient-oin (not= institution-oin recipient-oin)]}
  (let [soap-req (soap/prepare-soap-call opvragen-aangeboden-opleiding-soap-action
                                         [[:duo:aangebodenOpleidingCode id]]
                                         (rio.loader/make-datamap institution-oin
                                                                  recipient-oin)
                                         credentials)
        request  (assoc credentials
                   :url read-url
                   :method :post
                   :body soap-req
                   :headers {"SOAPAction" (str rio.loader/contract "/" opvragen-aangeboden-opleiding-soap-action)}
                   :content-type :xml)]
    (-> request
        http-utils/send-http-request
        (rio.loader/guard-getter-response type opvragen-aangeboden-opleiding-response-tagname)
        clj-xml/parse-str
        xml-seq
        (xml-utils/find-in-xmlseq #(and (aangeboden-opleiding-namen (:tag %)) %)))))

(defn generate-diff-ooapi-rio [{:keys [rio-summary ooapi-summary]}]
  (reduce (fn [h k]
            (assoc h k
                     (if (= (k rio-summary) (k ooapi-summary))
                       {:diff false}
                       {:diff     true
                        :current  (k rio-summary)
                        :proposed (k ooapi-summary)})))
          {}
          (into (set (keys ooapi-summary)) (keys rio-summary))))

(def dutch-locales ["nl-NL" "nl"])

(defn summarize-eduspec [eduspec]
  {:begindatum                    (:validFrom eduspec),
   :naamLang                      (common/get-localized-value (:name eduspec) dutch-locales),
   :naamKort                      (:abbreviation eduspec),
   :internationaleNaam            (common/get-localized-value (:name eduspec)),
   :omschrijving                  (common/get-localized-value (:description eduspec) dutch-locales),
   :eigenOpleidingseenheidSleutel (:educationSpecificationId eduspec)})

(defn summarize-course-program [course-program]
  (let [consumer (->> course-program
                      :consumers
                      (filter #(= "rio" (:consumerKey %)))
                      first)]
    {:onderwijsaanbiedercode       (:educationOffererCode consumer)
     :onderwijslocatiecode         (:educationLocationCode consumer)
     :eigenNaamAangebodenOpleiding (-> course-program
                                       :name
                                       (common/get-localized-value dutch-locales))
     :eigenNaamInternationaal      (-> course-program
                                       :name
                                       (common/get-localized-value))
     :eigenOmschrijving            (-> course-program
                                       :description
                                       (common/get-localized-value dutch-locales))
     :cohorten                     (-> course-program :offerings)}))

(defn summarize-cohort [cohort]
  (let [xmlseq (xml-seq cohort)
        ks     [:cohortcode :beginAanmeldperiode :eindeAanmeldperiode]]
    (reduce (fn [m k] (assoc m k (xml-utils/find-content-in-xmlseq xmlseq k))) {} ks)))

(defn summarize-offering [offering]
  {:cohortcode (-> offering :primaryCode :code)
   :beginAanmeldperiode (:enrollStartDate offering)
   :eindeAanmeldperiode (:enrollEndDate offering)})

(defn summarize-aangebodenopleiding [rio-obj]
  (when rio-obj
    (let [finder      (fn [k] (xml-utils/find-in-xmlseq (xml-seq rio-obj)
                                                        #(and (= k (:tag %))
                                                              (-> % :content first))))
          key-list    [:eigenNaamInternationaal :eigenOmschrijving :eigenNaamAangebodenOpleiding
                       :onderwijsaanbiedercode :onderwijslocatiecode]
          rio-summary (reduce (fn [m k] (assoc m k (finder k))) {} key-list)
          cohorten    (xml-utils/find-all-in-xmlseq (xml-seq rio-obj)
                                                    #(when (contains? cohortnamen (:tag %)) %))]
      (assoc rio-summary :cohorten (mapv summarize-cohort cohorten)))))

(defn compare-entities [rio-summary entity type]
  (let [summarizer (if (= "education-specification" type) summarize-eduspec summarize-course-program)
        ooapi-summary (summarizer entity)]
    (assoc (generate-diff-ooapi-rio {:rio-summary rio-summary :ooapi-summary ooapi-summary})
      :status "found")))
