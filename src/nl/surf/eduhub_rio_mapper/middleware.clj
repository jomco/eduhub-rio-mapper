(ns nl.surf.eduhub-rio-mapper.middleware
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [result-> errors?]]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]))

(defn generate-response [handler]
  (fn [request]
    {:pre [(map? request)]
     :post [(map? %)]}
    (let [response (handler request)]
      (assoc response :token (:uuid request)))))

;; Execute the request
(defn sync-action-processor [handler {:keys [handle-updated handle-deleted mutate]}]
  {:pre [(fn? mutate)
         (fn? handle-updated)]}
  (fn [request]
    {:pre [(map? request)]
     :post [(map? %)]}
    (let [{:keys [body] :as response} (handler request)
          {:keys [type data]} body]
      (case type
        :process (let [{:keys [id action type]} data
                       handler (case action "delete" handle-deleted
                                            "upsert" handle-updated)
                       payload {::ooapi/id      id
                                ::ooapi/type    type
                                :action         action
                                :institution-id nil}
                       result (result-> (handler payload)
                                        (mutate))]
                   (if (errors? result)
                     (assoc-in response [:body :errors] (:errors result))
                     response))
        :status response))))
