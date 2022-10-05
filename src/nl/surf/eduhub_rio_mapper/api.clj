(ns nl.surf.eduhub-rio-mapper.api
  (:require [clojure.tools.logging :as log]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [nl.surf.eduhub-rio-mapper.http :as http]
            [nl.surf.eduhub-rio-mapper.status :as status]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [ring.middleware.defaults :as defaults]
            [ring.middleware.json :refer [wrap-json-response]])
  (:import java.util.UUID))

(defn wrap-exception-catcher
  [app]
  (fn with-exception-catcher [req]
    (try
      (app req)
      (catch Throwable e
        (log/error "API handler exception caught" e)
        {:status http/internal-server-error}))))

(defn wrap-job-enqueuer
  [app enqueue-fn]
  (fn with-job-enqueuer [req]
    (let [{:keys [job] :as res} (app req)]
      (if job
        (let [token (UUID/randomUUID)]
          (enqueue-fn (assoc job :token token))
          (assoc res :body {:token token}))
        res))))

(defn wrap-status-getter
  [app config]
  (fn with-status-getter [req]
    (let [res (app req)]
      (if-let [token (:token res)]
        (if-let [status (status/get config token)]
          (assoc res
                 :status http/ok
                 :body status)
          (assoc res
                 :status http/not-found
                 :body {:status :unknown}))
        res))))

(def types {"courses"                  "course"
            "education-specifications" "education-specification"
            "programs"                 "program"})

(def actions #{"upsert" "delete"})

(defroutes routes
  (POST "/job/:action/:type/:id"
        {{:keys [action type id]} :params
         ;; TODO: Remove this header when API authentication is
         ;; implemented.
         {:strs [x-schac-home]}   :headers}
        (assert [x-schac-home])
        (let [type   (types type)
              action (actions action)]
          (when (and type action)
            {:job  {:action                 action,
                    :type                   type,
                    :id                     id
                    :institution-schac-home x-schac-home}})))

  (GET "/status/:token" [token]
       {:token token})

  (route/not-found nil))

(defn make-app [config]
  (-> routes
      (wrap-job-enqueuer (partial worker/enqueue! config))
      (wrap-status-getter config)
      (wrap-json-response)
      (wrap-exception-catcher)
      (defaults/wrap-defaults defaults/api-defaults)))
