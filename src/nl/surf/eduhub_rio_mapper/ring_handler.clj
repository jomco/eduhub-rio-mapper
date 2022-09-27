(ns nl.surf.eduhub-rio-mapper.ring-handler
  (:require [compojure.core :as cpj]
            [compojure.route :as route]
            [nl.surf.eduhub-rio-mapper.cli :as cli]
            [nl.surf.eduhub-rio-mapper.middleware :as mware]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :as middleware]
            [ring.util.response :as ring-response]))

(defn job-handler [id type]
  (ring-response/response {:success true, :type :process, :data {:id id, :action "upsert" :type type}}))

(defn deletion-job-handler [id type]
  (ring-response/response {:success true, :type :process, :data {:id id, :action "delete" :type type}}))

(defn status-handler [token]
  (ring-response/response {:success true, :type :status, :data {:token token}}))

(cpj/defroutes app-routes
               (cpj/POST "/job/upsert/education-specifications/:id" [id] (job-handler id "education-specification"))
               (cpj/POST "/job/upsert/programs/:id" [id] (job-handler id "program"))
               (cpj/POST "/job/upsert/courses/:id" [id] (job-handler id "course"))
               (cpj/POST "/job/delete/education-specifications/:id" [id] (deletion-job-handler id "education-specification"))
               (cpj/POST "/job/delete/programs/:id" [id] (deletion-job-handler id "program"))
               (cpj/POST "/job/delete/courses/:id" [id] (deletion-job-handler id "course"))
               (cpj/GET "/status/:token" [token] (status-handler token))
               (route/not-found "<h1>Page not quite found</h1>"))

(def app nil)

(defn make-app [handlers]
  (-> app-routes
      (mware/log-request)
      (mware/add-uuid)
      (mware/sync-action-processor handlers)
      (mware/generate-response)
      (middleware/wrap-json-body {:keywords? true :bigdecimals? true})
      (middleware/wrap-json-response)
      (defaults/wrap-defaults (merge defaults/site-defaults {:security {:anti-forgery false}
                                                             :params   {:keywordize true}}))))

(defn init []
  (alter-var-root (var app) (constantly (make-app (cli/load-config-from-env)))))
