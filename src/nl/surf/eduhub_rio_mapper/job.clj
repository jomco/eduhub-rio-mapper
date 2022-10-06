(ns nl.surf.eduhub-rio-mapper.job
  (:require [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi])
  (:refer-clojure :exclude [run!]))

(defn run!
  "Run given job and return result."
  [{:keys [handle-deleted handle-updated mutate]}
   {:keys [id type action institution-schac-home]}]
  (let [job {::ooapi/id              id
             ::ooapi/type            type
             :action                 action
             :institution-schac-home institution-schac-home}]
    (try
      (result-> ((case action
                   "delete" handle-deleted
                   "upsert" handle-updated) job) (mutate))
      (catch Exception ex
        (log/error "Job run failed" ex)
        {:errors {:job       job
                  :exception ex}}))))
