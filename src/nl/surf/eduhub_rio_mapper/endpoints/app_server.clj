(ns nl.surf.eduhub-rio-mapper.endpoints.app-server
  (:require [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server HttpConnectionFactory]))

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
