(ns nl.surf.eduhub-rio-mapper.api
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [nl.surf.eduhub-rio-mapper.errors :refer [result->]]
            [nl.surf.eduhub-rio-mapper.http :as http]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]]))

(defn wrap-sync-action-processor
  [handler {:keys [handle-updated
                   handle-deleted
                   mutate]}]
  {:pre [(fn? mutate) (fn? handle-updated)]}
  (fn [request]
    {:pre [(map? request)], :post [(map? %)]}
    (let [{{:keys [id action type]
            :as   job} :job
           :as         response} (handler request)]
      (if job
        (let [handler (case action
                        "delete" handle-deleted
                        "upsert" handle-updated)
              payload {::ooapi/id      id
                       ::ooapi/type    type
                       :action         action
                       :institution-id nil}
              result  (result-> (handler payload) (mutate))]
          (assoc-in response [:body :result] result))
        response))))

(def types {"courses"                  "course"
            "education-specifications" "education-specification"
            "programs"                 "program"})

(def actions #{"upsert" "delete"})

(defroutes routes
  (POST "/job/:action/:type/:id" [action type id]
        (let [type   (types type)
              action (actions action)]
          (when (and type action)
            {:job {:action action, :type type, :id id}})))

  (GET "/status/:token" [_] ;; TODO
       {:status http/not-found
        :body   {:status :unknown}})

  (route/not-found nil))

(defn make-app [handlers]
  (-> routes
      (wrap-sync-action-processor handlers)
      (wrap-json-response)
      (defaults/wrap-defaults defaults/secure-api-defaults)))
