(ns nl.surf.eduhub-rio-mapper.middleware
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi])
  (:import [java.util UUID]))

(defn generate-response [handler]
  (fn [request]
    (let [response (handler request)]
      (assoc response :token (:uuid request)))))

;; Execute the request
(defn sync-action-processor [handler {:keys [handle-updated handle-deleted mutate]}]
  {:pre [(and (not (nil? mutate))
              (not (nil? handle-updated)))]}
  (fn [request]
    (let [{:keys [body] :as response} (handler request)
          {:keys [type data]} body]
      (case type
        :process (let [{:keys [id action type]} data]
                   (case action
                     "upsert" (result->
                                (handle-updated {::ooapi/id      id
                                                 ::ooapi/type    type
                                                 :action         action
                                                 :institution-id nil})
                                (mutate))
                     "delete" (result->
                                (handle-deleted {::ooapi/id      id
                                                 ::ooapi/type    type
                                                 :action         action
                                                 :institution-id nil})
                                (mutate))
                     )
                   (dissoc response :process))
        :status response))))

;; Log request to stdout for debugging
(defn log-request [handler]
  (fn [request]
    (prn request)
    (let [response (handler request)]
      response)))

;; Generate UUID and add to request
(defn add-uuid [handler]
  (fn [request]
    (let [response (handler (assoc request :uuid (.toString (UUID/randomUUID))))]
      response)))
