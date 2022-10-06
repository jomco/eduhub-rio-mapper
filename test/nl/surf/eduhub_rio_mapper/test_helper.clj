(ns nl.surf.eduhub-rio-mapper.test-helper
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer :all]))

(defn load-json [path]
  (some-> path
          io/resource
          slurp
          (json/read-str :key-fn keyword)))
