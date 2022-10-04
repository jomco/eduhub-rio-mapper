(ns nl.surf.eduhub-rio-mapper.clients-info
  "Translate OOAPI SchacHome of institution to OIN for RIO."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.http :as http]))

(s/def ::client-info
  (s/keys :req-un [::institution-oin
                   ::institution-schac-home
                   ::client-id]))

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
  "Ensures client info is provided to the request.

  :client-id should be present in the request. If no info is found for
  the given client-id, the request is forbidden."
  [f clients]
  (fn [{:keys [client-id] :as request}]
    {:pre [client-id]}
    (if-let [info (client-info clients client-id)]
      (f (merge request info))
      {:status http/forbidden})))
