(ns nl.surf.rio-mapper.e2e.fixtures.entities
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [nl.surf.rio-mapper.e2e.fixtures.ids :as ids]
            [clojure.walk :as walk])
  (:import java.io.File java.util.regex.Pattern))


(defn- find-files
  ([^String path ^File f ^Pattern p]
   (if (.isDirectory f)
     (mapcat #(find-files (str path "/" (.getName %)) % p) (.listFiles f))
     (when (re-matches p (.getName f))
       [path])))
  ([^File f ^Pattern p]
   (find-files (.getName f) f p)))

(defn- entity-resources
  []
  (let [dir (-> "entities"
                io/resource
                io/file)]
    (when-not (.isDirectory dir)
      (throw (IllegalStateException. (str "entities is not a directory -- are you running in a jar?"))))
    (find-files dir #".*\.json$")))

(defn- load-entity
  [type name]
  (-> (str "entities/" type "/" name ".json")
      io/resource
      (io/reader :encoding "UTF-8")
      json/read-json))

(defn- load-entities
  []
  (reduce
   (fn [entities path]
     (when-let [[_ type name] (re-matches #"entities\/([^/]+)\/([^.]+)\.json" path)]
       (assoc-in entities [(keyword type) name] (load-entity type name))))
   {}
   (entity-resources)))

(defn fixtures
  []
  (let [entities (load-entities)
        names    (mapcat keys (vals entities))
        nameset  (ids/nameset names)]
    {:entities entities
     :nameset nameset}))

(defn- set-entity-ids
  [entity nameset session]
  (cond
    (sequential? entity)
    (mapv #(set-entity-ids % nameset session) entity)

    (map? entity)
    (reduce-kv (fn [m k v]
                 (assoc m k (set-entity-ids v nameset session)))
               {}
               entity)

    (and (string? entity)
         (ids/name? nameset entity))
    (ids/name->uuid nameset session entity)

    :else
    entity))

(defn get-entity
  [{:keys [entities nameset] :as _fixtures} type uuid]
  (let [session (ids/uuid->session uuid)]
    (-> (get-in entities [type (ids/uuid->name nameset uuid)])
        (set-entity-ids nameset session))))
