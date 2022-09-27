(ns nl.surf.eduhub-rio-mapper.middleware
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]))

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
        :process (let [{:keys [id action type]} data
                       payload {::ooapi/id      id
                                ::ooapi/type    type
                                :action         action
                                :institution-id nil}]
                   (case action
                     "delete" (result-> (handle-deleted payload)
                                        (mutate))
                     "upsert" (result-> (handle-updated payload)
                                        (mutate))
                   (dissoc response :process)))
        :status response))))
