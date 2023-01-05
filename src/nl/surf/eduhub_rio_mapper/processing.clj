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
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.dry-run :as dry-run]
    [nl.surf.eduhub-rio-mapper.logging :as logging]
    [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.relation-handler :as relation-handler]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid-finder :as opleenh-finder]
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
        (when-not (or code (= "education-specification" type) (= "delete" action))
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
                     (throw (ex-info (str "Entity not found in RIO after upsert: " type " " id) {})))]
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

(defn- make-dry-runner [{:keys [rio-config ooapi-loader resolver] :as _handlers}]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [type id] :keys [institution-oin onderwijsbestuurcode] :as request}]
    {:pre [(:institution-oin request)
           (s/valid? ::common/onderwijsbestuurcode onderwijsbestuurcode)]}
    (let [output
          (case type
            "education-specification"
            (let [rio-code    (resolver "education-specification" id institution-oin)
                  opl-eenheid (opleenh-finder/find-opleidingseenheid onderwijsbestuurcode
                                                                     rio-code
                                                                     institution-oin
                                                                     rio-config)
                  rio-summary (opleenh-finder/summarize-opleidingseenheid opl-eenheid)]
              (when rio-summary
                (assoc (dry-run/compare-entities rio-summary (ooapi-loader request) type)
                  :opleidingeenheidcode rio-code)))
            ("course" "program")
            (let [rio-obj     (dry-run/find-aangebodenopleiding id institution-oin rio-config)
                  rio-summary (dry-run/summarize-aangebodenopleiding rio-obj)]
              (when rio-summary
                (let [offering-summary (mapv dry-run/summarize-offering (ooapi.loader/load-offerings ooapi-loader request))
                      ooapi-entity     (assoc (ooapi-loader request)
                                         :offerings offering-summary)]
                  (assoc (dry-run/compare-entities rio-summary ooapi-entity type)
                    :aangebodenOpleidingCode (xml-utils/find-content-in-xmlseq (xml-seq rio-obj) :aangebodenOpleidingCode))))))]
      {:dry-run (assoc output :status (if output "found" "not-found"))})))

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
        dry-run!     (make-dry-runner handlers)]
    (assoc handlers :update! update!, :delete! delete!, :dry-run! dry-run!)))
