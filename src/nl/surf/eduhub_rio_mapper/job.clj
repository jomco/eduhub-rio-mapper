(ns nl.surf.eduhub-rio-mapper.job
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi])
  (:refer-clojure :exclude [run!]))

(defn run!
  "Run given job and return result."
  [{:keys [handle-deleted handle-updated mutate]}
   {:keys [id type action institution-schac-home]}]
  (let [handler (case action
                  "delete" handle-deleted
                  "upsert" handle-updated)
        job     {::ooapi/id              id
                 ::ooapi/type            type
                 :action                 action
                 :institution-schac-home institution-schac-home}]
    (result-> (handler job) (mutate))))
