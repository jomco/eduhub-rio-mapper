(ns nl.surf.eduhub-rio-mapper.job
  (:require [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi])
  (:refer-clojure :exclude [run!]))

(defn run!
  "Run given job and return result."
  [{:keys [handle-deleted handle-updated mutate]}
   {:keys [id type action institution-schac-home institution-oin]}]
  {:pre [id type action institution-schac-home institution-oin
         handle-deleted handle-updated mutate]}
  (log/info (format "Started job, action %s, type %s, id %s" action type id))
  (let [job {::ooapi/id              id
             ::ooapi/type            type
             :action                 action
             :institution-schac-home institution-schac-home
             :institution-oin        institution-oin}]
    (try
      (result-> ((case action
                   "delete" handle-deleted
                   "upsert" handle-updated) job) (mutate))
      (catch Exception ex
        (log/error ex "Job run failed" job)
        {:errors {:phase    ;; TODO the following is not very accurate
                            ;; because the mapper does not handle the
                            ;; unhappy paths very well (http request
                            ;; responding with 404 etc.
                  (case action
                             "delete" :deleting
                             "upsert" :upserting)
                  :message "RIO Mapper internal error"}}))))
