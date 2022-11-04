(ns nl.surf.eduhub-rio-mapper.processing
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
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

(defn- make-updater-load-ooapi-phase [{:keys [ooapi-loader]}]
  (let [validating-loader (ooapi.loader/validating-loader ooapi-loader)]
    (fn load-ooapi-phase [request]
      (ooapi.loader/load-entities validating-loader request))))

(defn- make-updater-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{:keys [institution-oin] ::rio/keys [opleidingscode] :as request}]
    {:pre [institution-oin]}
    (let [code (or opleidingscode
                   (resolver "education-specification"
                             (updated-handler/education-specification-id request)
                             institution-oin))]
      (merge request (and code {::rio/opleidingscode code})))))

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
    (let [mutate-result (mutator/mutate! result rio-config)]
      {:job job :eduspec eduspec :mutate-result mutate-result})))

(defn- make-updater-confirm-rio-phase [{:keys [resolver]}]
  (fn confirm-rio-phase [{{::ooapi/keys [id type] :keys [institution-oin] :as job} :job mutate-result :mutate-result eduspec :eduspec}]
    (let [rio-code (when-not (blocking-retry #(resolver type id institution-oin)
                                             [5 30 120 600]
                                             "Ensure upsert is processed by RIO")
                     (throw (ex-info "Entity not found in RIO after upsert." {:id id})))]
      (if (= type "education-specification")
        {:job job :eduspec (assoc eduspec ::rio/opleidingscode rio-code) :mutate-result mutate-result}
        {:job job :eduspec eduspec :mutate-result mutate-result}))))

(defn- make-updater-sync-relations-phase [handlers]
  (fn sync-relations-phase [{:keys [job eduspec] :as request}]
    (when eduspec
      (relation-handler/after-upsert eduspec job handlers))
    request))

(defn- wrap-phase [[phase f]]
  (fn [req]
    (try
      (f req)
      (catch Exception e
        (let [phase (or (-> e (ex-data) :phase) phase)]
          (throw (ex-info (str "Error during phase " phase) {:phase phase} e)))))))

(defn- make-update [handlers]
  (let [fs [[:fetching-ooapi    (make-updater-load-ooapi-phase handlers)]
            [:resolving         (make-updater-resolve-phase handlers)]
            [:preparing         (make-updater-soap-phase)]
            [:upserting         (make-updater-mutate-rio-phase handlers)]
            [:confirming        (make-updater-confirm-rio-phase handlers)]
            [:associating       (make-updater-sync-relations-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(defn- make-deleter [{:keys [rio-config] :as handlers}]
  {:pre [rio-config]}
  (let [fs [[:resolving         (make-updater-resolve-phase handlers)]
            [:deleting          (make-deleter-prune-relations-phase handlers)]
            [:preparing         (make-deleter-soap-phase)]
            [:deleting          (make-updater-mutate-rio-phase handlers)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(defn make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials]}]
  {:pre [(:recipient-oin rio-config)]}
  (let [resolver     (rio.loader/make-resolver rio-config)
        getter       (rio.loader/make-getter rio-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader gateway-root-url
                                                          gateway-credentials)
        handlers     {:ooapi-loader   ooapi-loader
                      :rio-config     rio-config
                      :getter         getter
                      :resolver       resolver}
        update!      (make-update handlers)
        delete!      (make-deleter handlers)]
    (assoc handlers :update! update!, :delete! delete!)))
