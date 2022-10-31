(ns nl.surf.eduhub-rio-mapper.clients-info
  "Translate OOAPI SchacHome of institution to OIN for RIO."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.logging :refer [with-mdc]]))

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

(defn add-client-info
  "Provide client info to the request and the response.

  :client-id should be present in the request. If no info is found for
  the given client-id, the request is forbidden, otherwise client info
  is also added to the response."
  [f clients {:keys [client-id] :as request}]
  {:pre [client-id]}
  (if-let [info (client-info clients client-id)]
    (with-mdc info
              ;; set info on request and response, so we can log client info
              ;; in the response phase as well as in the wrapped handler
              (-> request
                  (merge info)
                  f
                  (merge info)))
    {:status http-status/forbidden}))
