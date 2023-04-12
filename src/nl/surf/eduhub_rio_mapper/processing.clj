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

(ns nl.surf.eduhub-rio-mapper.processing
  (:require
    [clojure.data.xml :as clj-xml]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.dry-run :as dry-run]
    [nl.surf.eduhub-rio-mapper.logging :as logging]
    [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.relation-handler :as relation-handler]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(defn- extract-eduspec-from-result [result]
  (let [entity (:ooapi result)]
    (when (= "aanleveren_opleidingseenheid" (:action result))
      entity)))

(defn blocking-retry
  "Calls f and retries if it returns nil.

  Sleeps between each invocation as specified in retry-delays-seconds.
  Returns return value of f when successful.
  Returns nil when as many retries as delays have taken place. "
  [f retry-delays-seconds action]
  (loop [retry-delays-seconds retry-delays-seconds]
    (or
      (f)
      (when-not (empty? retry-delays-seconds)
        (let [[head & tail] retry-delays-seconds]
          (log/warn (format "%s failed - sleeping for %s seconds." action head))
          (Thread/sleep (long (* 1000 head)))
          (recur tail))))))

(defn- make-updater-load-ooapi-phase [{:keys [ooapi-loader]}]
  (let [validating-loader (ooapi.loader/validating-loader ooapi-loader)]
    (fn load-ooapi-phase [{::ooapi/keys [type id] :as request}]
      (logging/with-mdc
        {:ooapi-type type :ooapi-id id}
        (ooapi.loader/load-entities validating-loader request)))))

(defn- make-updater-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{::ooapi/keys [type] :keys [institution-oin action] ::rio/keys [opleidingscode] :as request}]
    {:pre [institution-oin]}
    (if opleidingscode
      (assoc request ::rio/opleidingscode opleidingscode)
      (let [id   (updated-handler/education-specification-id request)
            code (resolver "education-specification" id institution-oin)]
        ;; Inserting a course or program while the education
        ;; specification has not been added to RIO will throw an
        ;; error.
        ;; Also throw an error when trying to delete an education specification
        ;; that cannot be resolved.
        (when (or (and (nil? code) (not= "education-specification" type) (= "upsert" action))
                  (and (nil? code) (= "education-specification" type) (= "delete" action)))
          (throw (ex-info (str "No education specification found with id: " id)
                          {:code code
                           :type type
                           :action action
                           :retryable? false})))
        (assoc request ::rio/opleidingscode code)))))

(defn- make-updater-soap-phase []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/update-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-deleter-prune-relations-phase [handlers]
  (fn [{::ooapi/keys [type] ::rio/keys [opleidingscode] :keys [institution-oin] :as request}]
    (when (and opleidingscode (= type "education-specification"))
      (relation-handler/delete-relations opleidingscode type institution-oin handlers))
    request))

(defn- make-deleter-soap-phase []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/deletion-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-updater-mutate-rio-phase [{:keys [rio-config]}]
  (fn mutate-rio-phase [{:keys [job result eduspec]}]
    {:pre [(s/valid? ::Mutation/mutation-response result)]}
    (logging/with-mdc
      {:soap-action (:action result) :ooapi-id (::ooapi/id job)}
      {:job job :eduspec eduspec :mutate-result (mutator/mutate! result rio-config)})))

(defn- make-updater-confirm-rio-phase [{:keys [resolver]} rio-config]
  (fn confirm-rio-phase [{{::ooapi/keys [id type]
                           :keys        [institution-oin]
                           :as          job} :job
                          mutate-result      :mutate-result
                          eduspec            :eduspec}]
    (let [rio-code (when-not (blocking-retry #(resolver type id institution-oin)
                                             (:rio-retry-attempts-seconds rio-config)
                                             "Ensure upsert is processed by RIO")
                     (throw (ex-info (str "Processing this job takes longer than expected. Our developers have been informed and will contact DUO. Please try again in a few hours."
                                          ": " type " " id) {})))]
      (if (= type "education-specification")
        {:job           job
         :eduspec       (assoc eduspec ::rio/opleidingscode rio-code)
         :mutate-result mutate-result}
        {:job           job
         :eduspec       eduspec
         :mutate-result mutate-result}))))

(defn- make-updater-sync-relations-phase [handlers]
  (fn sync-relations-phase [{:keys [job eduspec] :as request}]
    (when eduspec
      (relation-handler/after-upsert eduspec job handlers))
    request))

(defn- wrap-phase [[phase f]]
  (fn [req]
    (try
      (f req)
      (catch Exception ex
        (throw (ex-info (ex-message ex)
                        (assoc (ex-data ex) :phase phase)
                        ex))))))

(defn- make-update [handlers rio-config]
  (let [fs [[:fetching-ooapi (make-updater-load-ooapi-phase handlers)]
            [:resolving      (make-updater-resolve-phase handlers)]
            [:preparing      (make-updater-soap-phase)]
            [:upserting      (make-updater-mutate-rio-phase handlers)]
            [:confirming     (make-updater-confirm-rio-phase handlers rio-config)]
            [:associating    (make-updater-sync-relations-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(defn- make-deleter [{:keys [rio-config] :as handlers}]
  {:pre [rio-config]}
  (let [fs [[:resolving (make-updater-resolve-phase handlers)]
            [:deleting  (make-deleter-prune-relations-phase handlers)]
            [:preparing (make-deleter-soap-phase)]
            [:deleting  (make-updater-mutate-rio-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(def opleidingseenheid-namen #{:hoOpleiding :particuliereOpleiding :hoOnderwijseenhedencluster :hoOnderwijseenheid})

(defn find-opleidingseenheid [rio-code getter institution-oin]
  {:pre [rio-code]}
  (-> (getter {::rio/type       rio.loader/opleidingseenheid ::ooapi/id rio-code
               :institution-oin institution-oin :response-type :xml})
      clj-xml/parse-str
      xml-seq
      (xml-utils/find-in-xmlseq #(when (opleidingseenheid-namen (:tag %)) %))))

(defn- duo-string [x]
  (str "duo:" (if (keyword? x) (name x) x)))

(defn- duo-keyword [x]
  (keyword (duo-string x)))

(defn- xmlclj->duo-hiccup [x]
  {:pre [x (:tag x)]}
  (into
    [(duo-keyword (:tag x))]
    (mapv #(if (:tag %) (xmlclj->duo-hiccup %) %)
         (:content x))))

(defn- eduspec-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin]} {:keys [resolver getter]}]
  (let [rio-code      (resolver "education-specification" id institution-oin)
        rio-summary   (some-> rio-code
                              (find-opleidingseenheid getter institution-oin)
                              (dry-run/summarize-opleidingseenheid))
        ooapi-summary (dry-run/summarize-eduspec ooapi-entity)
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :opleidingeenheidcode rio-code))]
    (assoc output :status (if ooapi-summary "found" "not-found"))))

(defn- course-program-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin] :as request} {:keys [rio-config ooapi-loader]}]
  (let [rio-obj     (dry-run/find-aangebodenopleiding id institution-oin rio-config)
        rio-summary (when rio-obj (dry-run/summarize-aangebodenopleiding-xml rio-obj))
        offering-summary (mapv dry-run/summarize-offering (ooapi.loader/load-offerings ooapi-loader request))
        ooapi-summary (dry-run/summarize-course-program (assoc ooapi-entity :offerings offering-summary))
        rio-code (when rio-obj (xml-utils/find-content-in-xmlseq (xml-seq rio-obj) :aangebodenOpleidingCode))
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :aangebodenOpleidingCode rio-code))]
    (assoc output :status (if ooapi-summary "found" "not-found"))))

(defn- make-dry-runner [{:keys [rio-config ooapi-loader] :as handlers}]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [type] :as request}]
    {:pre [(:institution-oin request)]}
    (let [ooapi-entity (ooapi-loader request)
          value (if (nil? ooapi-entity)
                  {:status "not-found"}
                  (let [handler (case type "education-specification" eduspec-dry-run-handler
                                           ("course" "program") course-program-dry-run-handler)]
                    (handler ooapi-entity request handlers)))]
      {:dry-run value})))

(defn sleutel-finder [sleutel-name]
  (fn [element]
    (when (and (sequential? element)
               (= [:duo:kenmerken [:duo:kenmerknaam sleutel-name]]
                  (vec (take 2 element))))
      (-> element last last))))

(defn sleutel-changer [id finder]
  (fn [element]
    (if (finder element)
      (assoc-in element [2 1] id)
      element)))

(defn- rio-finder [getter rio-config {::ooapi/keys [type] ::rio/keys [code] :keys [institution-oin] :as _request}]
  {:pre [code]}
  (case type
    "education-specification" (find-opleidingseenheid code getter institution-oin)
    ("course" "program")      (dry-run/find-aangebodenopleiding code institution-oin rio-config)))

(defn attribute-adapter [rio-obj k]
  (some #(and (sequential? %)
              (or
                (and (= (duo-keyword k) (first %))
                     (last %))
                (and (= :duo:kenmerken (first %))
                     (= (name k) (get-in % [1 1]))
                     (get-in % [2 1]))))
        rio-obj))

(defn- wrap-attribute-adapter-STAP [adapter]
  (fn [rio-obj k]
    (or (adapter rio-obj k)
        (when (= k :toestemmingDeelnameSTAP)
          "GEEN_TOESTEMMING_VERLEEND"))))

(declare link-item-adapter)

(defn child-adapter [rio-obj k]
  (->> rio-obj
       (filter #(and (sequential? %)
                     (= (duo-keyword k) (first %))
                     %))
       (map #(partial link-item-adapter %))))

(defn strip-duo [kw]
  (-> kw
      name
      (str/replace #"^duo:" "")))

;; Turns <prijs><soort>s</soort><bedrag>123</bedrag></prijs> into {:soort "s", bedrag 123}
(defn- nested-adapter [rio-obj k]
  (keep #(when (and (sequential? %)
                    (= (duo-keyword k) (first %)))
           (zipmap (map (comp keyword strip-duo first) (rest %))
                   (map last (rest %))))
        rio-obj))

;; These attributes have nested elements, e.g.:
;; <prijs>
;;   <bedrag>99.50</bedrag>
;;   <soort>collegegeld</soort>
;; </prijs
(def attributes-with-children #{:vastInstroommoment :prijs :flexibeleInstroom})

(defn- link-item-adapter [rio-obj k]
  (if (string? k)
    (child-adapter rio-obj k)         ; If k is a string, it refers to a nested type: Periode or Cohort.
    (if (attributes-with-children k)  ; These attributes are the only ones with child elements.
      (vec (nested-adapter rio-obj k))
      ; The common case is handling attributes.
      ((wrap-attribute-adapter-STAP attribute-adapter) rio-obj k))))

(defn linker [rio-obj]
  (rio/->xml (partial link-item-adapter rio-obj)
             (-> rio-obj first strip-duo)))

(defn- make-linker [rio-config getter]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [id type] :keys [institution-oin] :as request}]
    {:pre [(:institution-oin request)]}
    (let [[action sleutelnaam]
          (case type
            "education-specification" ["aanleveren_opleidingseenheid" "eigenOpleidingseenheidSleutel"]
             ("course" "program")     ["aanleveren_aangebodenOpleiding" "eigenAangebodenOpleidingSleutel"])

          rio-obj  (rio-finder getter rio-config request)]
      (if (nil? rio-obj)
        (throw (ex-info "404 Not Found" {:phase :resolving}))
        (let [rio-obj  (xmlclj->duo-hiccup rio-obj)
              rio-obj (map #(if (and (sequential? %)
                                     (= :duo:opleidingseenheidcode (first %)))
                              (assoc % 0 :duo:opleidingseenheidSleutel)
                              %)
                           rio-obj)
              finder   (sleutel-finder sleutelnaam)
              old-id   (some finder rio-obj)
              new-id   id
              rio-new  (mapv (sleutel-changer new-id finder) rio-obj)
              result   {(keyword sleutelnaam) (if (= old-id new-id)
                                                {:diff false}
                                                {:diff true :old-id old-id :new-id new-id})}
              mutation {:action     action
                        :rio-sexp   [(linker rio-new)]
                        :sender-oin institution-oin}
              _success  (mutator/mutate! mutation rio-config)]
          {:link result})))))

(defn make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials]}]
  {:pre [(:recipient-oin rio-config)]}
  (let [resolver     (rio.loader/make-resolver rio-config)
        getter       (rio.loader/make-getter rio-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader gateway-root-url
                                                          gateway-credentials
                                                          rio-config)
        handlers     {:ooapi-loader ooapi-loader
                      :rio-config   rio-config
                      :getter       getter
                      :resolver     resolver}
        update!      (make-update handlers rio-config)
        delete!      (make-deleter handlers)
        dry-run!     (make-dry-runner handlers)
        link!        (make-linker rio-config getter)]
    (assoc handlers :update! update!, :delete! delete!, :dry-run! dry-run!, :link! link!)))
