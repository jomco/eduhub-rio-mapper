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

(ns nl.surf.eduhub-rio-mapper.cli-commands
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.endpoints.api :as api]
            [nl.surf.eduhub-rio-mapper.endpoints.worker-api :as worker-api]
            [nl.surf.eduhub-rio-mapper.job :as job]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(defn parse-getter-args [[type id & [pagina]]]
  {:pre [type id (string? type)]}
  (let [[type response-type] (reverse (str/split type #":" 2))
        response-type (and response-type (keyword response-type))]
    (assert (rio.loader/valid-get-types type))
    (-> (when pagina {:pagina pagina})
        (assoc (if (rio.loader/aangeboden-opleiding-types type)
                 ::ooapi/id
                 ::rio/opleidingscode) id
               :response-type response-type
               ::rio/type type))))

(defn parse-client-info-args [args clients]
  (let [[client-id & rest-args] args
        client-info (clients-info/client-info clients client-id)]
    (when (nil? client-info)
      (.println *err* (str "No client info found for client id " client-id))
      (System/exit 1))
    [client-info rest-args]))

(defn process-command [command args {{:keys [getter resolver ooapi-loader dry-run! link!] :as handlers} :handlers {:keys [clients] :as config} :config}]
  {:pre [getter]}
  (case command
    "serve-api"
    (api/serve-api config)

    "worker"
    ; Before starting the worker, start a http server solely for the health endpoint as a daemon thread
    (let [thread (new Thread ^Runnable #(worker-api/serve-api config))]
      (.setDaemon thread true)
      (.start thread)
      (worker/wait-worker
        (worker/start-worker! config)))

    "get"
    (let [[client-info rest-args] (parse-client-info-args args clients)]
      (getter (assoc (parse-getter-args rest-args)
                :institution-oin (:institution-oin client-info))))

    ("show" "dry-run-upsert")
    (let [[client-info [type id]] (parse-client-info-args args clients)
          request (merge client-info {::ooapi/id id ::ooapi/type type})
          handler (if (= "show" command) ooapi-loader dry-run!)]
      (handler request))

    "link"
    (let [[client-info [code type id]] (parse-client-info-args args clients)
          codename (if (= type "education-specification") ::rio/opleidingscode ::rio/aangeboden-opleiding-code)
          request (merge client-info {::ooapi/id id ::ooapi/type type codename code})]
      (link! request))

    "resolve"
    (let [[client-info [type id]] (parse-client-info-args args clients)]
      (resolver type id (:institution-oin client-info)))

    ("upsert" "delete" "delete-by-code")
    (let [[client-info [type id rest-args]] (parse-client-info-args args clients)
          job (merge (assoc client-info
                       ::ooapi/type type
                       :args rest-args)
                     (if (= "delete-by-code" command)
                       (let [name-id (if (= type "education-specification")
                                       ::rio/opleidingscode
                                       ::rio/aangeboden-opleiding-code)]
                         {:action "delete"
                          name-id id})
                       {:action    command
                        ::ooapi/id id}))]
      (job/run! handlers job (= (System/getenv "STORE_HTTP_REQUESTS") "true")))))
