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

(ns nl.surf.eduhub-rio-mapper.job
  (:require [clojure.tools.logging :as log]
            [nl.jomco.ring-trace-context :refer [with-context]]
            [nl.surf.eduhub-rio-mapper.logging :as logging]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import java.util.UUID)
  (:refer-clojure :exclude [run!]))

(defn run!
  "Run given job and return result."
  [{:keys [delete! update!]}
   {::ooapi/keys [id type]
    ::rio/keys [opleidingscode]
    :keys [action institution-schac-home institution-oin trace-context] :as request}]
  {:pre [(or id opleidingscode) type action institution-schac-home institution-oin
         delete! update!]}
  (log/infof "Started job, action %s, type %s, id %s" action type id)
  (let [job (select-keys request [:action :args :institution-oin :institution-schac-home
                                  ::rio/opleidingscode ::ooapi/type ::ooapi/id])]
    (try
      (with-context trace-context
        (case action
          "delete" (delete! job)
          "upsert" (update! job)))
      (catch Exception ex
        (let [error-id          (UUID/randomUUID)
              {:keys [phase
                      message]} (ex-data ex)]
          (logging/log-exception ex error-id)
          {:errors {:error-id      error-id
                    :trace-context trace-context

                    :phase   (or phase :unknown)
                    :message (or message :internal)

                    ;; We consider all exceptions retryable because
                    ;; something unexpected happened and hopefully it
                    ;; won't next time we try.
                    :retryable? true}})))))
