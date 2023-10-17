(ns nl.surf.rio-mapper.e2e.fixtures.server
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET routes]]
            [compojure.route :as route]
            [nl.surf.rio-mapper.e2e.fixtures.entities :as entities]
            [ring.adapter.jetty9 :refer [run-jetty]]))

(defn mk-app
  [fixtures]
  (-> (routes
       (GET "/:type/:uuid" [type uuid]
         (log/info "GET" type uuid)
         (when-let [entity (entities/get-entity fixtures (keyword type) uuid)]
           {:status       200
            :content-type "application/json; charset=utf-8"
            :body         (json/write-str entity)}))

       (route/not-found "Entity not found"))))

(defn start-server
  [port]
  (run-jetty (mk-app (entities/fixtures))
             {:join? false
              :port port}))

(defn stop-server
  [s]
  (.stop s))
