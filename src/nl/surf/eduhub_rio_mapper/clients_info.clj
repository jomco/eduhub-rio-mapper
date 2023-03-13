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

(ns nl.surf.eduhub-rio-mapper.clients-info
  "Translate OOAPI SchacHome of institution to OIN for RIO."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.logging :refer [with-mdc]]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]))

(s/def ::client-info
  (s/keys :req-un [::institution-oin
                   ::institution-schac-home
                   ::client-id
                   ::common/onderwijsbestuurcode]))

(s/def ::clients
  (s/coll-of ::client-info))

(s/def ::data
  (s/keys :req-un [::clients]))

(defn read-clients-data
  [{:keys [path]}]
  (let [data    (-> path (io/reader) (json/read-json true))]
    (when-let [problems (s/explain-data ::data data)]
      (throw (ex-info "OIN Mapper client configuration data has issues"
                      {:data data
                       :problems problems})))
    (:clients data)))

(defn client-info
  [clients client-id]
  (->> clients
       (filter #(= client-id (:client-id %)))
       first))

(defn institution-schac-homes
  "Collection of all configured institution-schac-homes."
  [clients]
  (map :institution-schac-home clients))

(defn wrap-client-info
  "Provide client info to the request and the response.

  It is the responsibility of authentication/wrap-authentication to ensure that :client-id
  be present in the request. If no info is found for the given client-id, the request is
  forbidden, otherwise client info is also added to the response."
  [f clients]
  (fn [{:keys [client-id] :as request}]
    (if-let [info (client-info clients client-id)]
      (with-mdc info
        ;; set info on request and response, so we can log client info
        ;; in the response phase as well as in the wrapped handler
        (-> request
            (merge info)
            f
            (merge info)))
      {:status http-status/forbidden})))
