(ns nl.surf.eduhub-rio-mapper.processing
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.Mutation :as-alias Mutation]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.relation-handler :as relation-handler]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]))

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

(defn- make-updater-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{:keys [institution-oin] ::rio/keys [opleidingscode] :as request}]
    (let [code (or opleidingscode
                   (-> request
                       (updated-handler/education-specification-id)
                       (resolver institution-oin)))]
      (merge request (and code {::rio/opleidingscode code})))))

(defn- make-updater-load-ooapi-phase [{:keys [ooapi-loader]}]
  (let [validating-loader (ooapi.loader/validating-loader ooapi-loader)]
    (fn load-ooapi-phase [request]
      (ooapi.loader/load-entities validating-loader request))))

(defn- make-updater-soap-phase []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/update-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-updater-mutate-rio-phase [{:keys [mutate]}]
  (fn mutate-rio-phase [{:keys [job result eduspec]}]
    {:pre [(s/valid? ::Mutation/mutation-response result)]}
    (let [mutate-result (mutate result)]
      {:job job :eduspec eduspec :mutate-result mutate-result})))

(defn- make-updater-confirm-rio-phase [{:keys [resolver]}]
  (fn confirm-rio-phase [{{::ooapi/keys [id type] :keys [institution-oin] :as job} :job mutate-result :mutate-result eduspec :eduspec}]
    ;; ^^-- skip check for courses and
    ;; programs, since resolver doesn't
    ;; work for them yet
    (or (not= "education-specification" type)
        (blocking-retry #(resolver id institution-oin)
                        [30 120 600]
                        "Ensure upsert is processed by RIO")
        (throw (ex-info "Entity not found in RIO after upsert." {:id id})))
    {:job job :eduspec eduspec :mutate-result mutate-result}))

(defn- make-updater-sync-relations-phase [handlers]
  (fn sync-relations-phase [{:keys [job mutate-result eduspec]}]
    ;; ^^-- skip check for courses and
    ;; programs, since resolver doesn't
    ;; work for them yet
    (when eduspec
      (relation-handler/after-upsert eduspec job handlers))
    mutate-result))

(defn- make-deleter [{:keys [mutate] :as handlers}]
  (fn [{::ooapi/keys [type] ::rio/keys [opleidingscode] :keys [institution-oin] :as job}]
    (when (and opleidingscode (= type "education-specification"))
      (relation-handler/delete-relations opleidingscode type institution-oin handlers))
    (-> job
        updated-handler/deletion-mutation
        mutate)))

(defn- wrap-phase [[phase f]]
  (fn [req]
    (try
      (f req)
      (catch Exception e
        (let [phase (or (-> e (ex-data) :phase) phase)]
          (throw (ex-info (str "Error during phase " phase) {:phase phase} e)))))))

(defn- make-update [handlers]
  (let [fs [[:resolving         (make-updater-resolve-phase handlers)]
            [:fetching-ooapi    (make-updater-load-ooapi-phase handlers)]
            [:make-soap         (make-updater-soap-phase)]
            [:updating-rio      (make-updater-mutate-rio-phase handlers)]
            [:confirming        (make-updater-confirm-rio-phase handlers)]
            [:syncing-relations (make-updater-sync-relations-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      (reduce (fn [req f] (f req)) request wrapped-fs))))

(defn make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials]}]
  (let [resolver     (rio.loader/make-resolver rio-config)
        getter       (rio.loader/make-getter rio-config)
        mutate       (mutator/make-mutator rio-config
                                           http-utils/send-http-request)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader gateway-root-url
                                                          gateway-credentials)
        handlers     {:ooapi-loader ooapi-loader
                      :mutate       mutate
                      :getter       getter
                      :resolver     resolver}
        update!      (make-update handlers)
        delete!      (updated-handler/wrap-resolver (make-deleter handlers) resolver)]
    (assoc handlers :update! update!, :delete! delete!)))
