(ns nl.surf.eduhub-rio-mapper.ring-handler
  (:require [compojure.core :refer [defroutes POST GET]]
            [compojure.route :as route]
            [nl.surf.eduhub-rio-mapper.cli :as cli]
            [nl.surf.eduhub-rio-mapper.middleware :refer [sync-action-processor generate-response]]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.util.response :as response]))

(defn job-handler [id type]
  {:post [(map? %)]}
  (response/response {:success true, :type :process, :data {:id id, :action "upsert" :type type}}))

(defn deletion-job-handler [id type]
  {:post [(map? %)]}
  (response/response {:success true, :type :process, :data {:id id, :action "delete" :type type}}))

(defn status-handler [token]
  {:post [(map? %)]}
  (response/response {:success true, :type :status, :data {:token token}}))

(defroutes app-routes
           (POST "/job/upsert/education-specifications/:id" [id] (job-handler id "education-specification"))
           (POST "/job/upsert/programs/:id" [id] (job-handler id "program"))
           (POST "/job/upsert/courses/:id" [id] (job-handler id "course"))
           (POST "/job/delete/education-specifications/:id" [id] (deletion-job-handler id "education-specification"))
           (POST "/job/delete/programs/:id" [id] (deletion-job-handler id "program"))
           (POST "/job/delete/courses/:id" [id] (deletion-job-handler id "course"))
           (GET "/status/:token" [token] (status-handler token))
           (route/not-found "<h1>Page not quite found</h1>"))

(def app nil)

(defn make-app [handlers]
  (-> app-routes
      (sync-action-processor handlers)
      (generate-response)
      (wrap-json-body {:keywords? true :bigdecimals? true})
      (wrap-json-response)
      (defaults/wrap-defaults (merge defaults/site-defaults {:security {:anti-forgery false}
                                                             :params   {:keywordize true}}))))

(defn init []
  (alter-var-root (var app) (constantly (make-app (cli/make-handlers)))))
