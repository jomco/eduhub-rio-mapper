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
            [nl.surf.eduhub-rio-mapper.http-utils :refer [*http-log*]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import java.util.UUID)
  (:refer-clojure :exclude [run!]))

(defn run!
  "Run given job and return result."
  [{:keys [delete! update!]}
   {::ooapi/keys [id type]
    ::rio/keys   [opleidingscode]
    :keys        [token action institution-schac-home institution-oin trace-context] :as request}]
  {:pre [(or id opleidingscode) type action institution-schac-home institution-oin
         delete! update!]}
  (let [log-context (assoc trace-context
                      :token token
                      :institution-schac-home institution-schac-home
                      :institution-oin institution-oin)
        job         (select-keys request [:action :args :institution-oin :institution-schac-home
                                          ::rio/opleidingscode ::ooapi/type ::ooapi/id])]
    (logging/with-mdc log-context
      (log/infof "Started job %s, action %s, type %s, id %s" token action type id)
      (binding [*http-log* (atom [])]
        (try
          (with-context trace-context
            (case action
              "delete" (assoc (delete! job)
                              :http-log @*http-log*)
              "upsert" (update! job)))
          (catch Exception ex
            (let [error-id                   (UUID/randomUUID)
                  {:keys [phase retryable?]} (ex-data ex)]
              (logging/log-exception ex error-id)
              {:errors {:error-id      error-id
                        :trace-context trace-context
                        :phase         (or phase :unknown)
                        :message       (ex-message ex)
                        :http-log      @*http-log*
                        ;; we default to retrying, since that captures
                        ;; all kinds of unexpected issues.
                        :retryable?    (not= retryable? false)}})))))))
