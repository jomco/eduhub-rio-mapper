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

(ns nl.surf.eduhub-rio-mapper.endpoints.app-server
  (:require [nl.jomco.http-status-codes :as http-status]
            [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server HttpConnectionFactory]))

(defn wrap-not-found-handler [app msg]
  (fn [req]
    (let [response (app req)]
      (if (= http-status/not-found (:status response))
        (assoc response :body (str msg (:body response)))
        response))))

(defn run-jetty [app host port]
  (jetty/run-jetty app
                   {:host         host
                    :port         port
                    :join?        true
                    ;; Configure Jetty to not send server version
                    :configurator (fn [jetty]
                                    (doseq [connector (.getConnectors jetty)]
                                      (doseq [connFact (.getConnectionFactories connector)]
                                        (when (instance? HttpConnectionFactory connFact)
                                          (.setSendServerVersion (.getHttpConfiguration connFact) false)))))}))
