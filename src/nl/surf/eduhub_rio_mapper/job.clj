(ns nl.surf.eduhub-rio-mapper.job
  (:require [clojure.tools.logging :as log]
            [nl.jomco.ring-trace-context :refer [with-context]]
            [nl.surf.eduhub-rio-mapper.logging :as logging]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi])
  (:import java.util.UUID)
  (:refer-clojure :exclude [run!]))

(defn run!
  "Run given job and return result."
  [{:keys [delete-and-mutate update-and-mutate]}
   {:keys [id type action args institution-schac-home institution-oin
           trace-context]}]
  {:pre [id type action institution-schac-home institution-oin
         delete-and-mutate update-and-mutate]}
  (log/infof "Started job, action %s, type %s, id %s" action type id)
  (let [job {::ooapi/id              id
             ::ooapi/type            type
             :action                 action
             :args                   args
             :institution-schac-home institution-schac-home
             :institution-oin        institution-oin}]
    (try
      (with-context trace-context
        (case action
          "delete" (delete-and-mutate job)
          "upsert" (update-and-mutate job)))
      (catch Exception ex
        (let [error-id (UUID/randomUUID)]
          (logging/log-exception ex error-id)
          {:errors {:error-id error-id
                    :trace-context trace-context
                    ;; TODO the following is not very accurate
                    ;; because the mapper does not handle the
                    ;; unhappy paths very well (http request
                    ;; responding with 404 etc.
                    :phase    (case action
                                "delete" :deleting
                                "upsert" :upserting)
                    :message  "RIO Mapper internal error"}})))))
