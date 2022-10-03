(ns nl.surf.eduhub-rio-mapper.oin-mapper
  "Translate OOAPI SchacHome of institution to OIN for RIO."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- read-mapping
  [{:keys [path]}]
  (let [data    (-> path (io/reader) (json/read-json false))
        mapping (get data "oins")]
    (when-not (seq mapping)
      (throw (ex-info "OIN Mapper configuration has no or empty \"oins\" entry." {:configuration data})))
    mapping))

(defn make-oin-mapper
  "Make a mapper function from institution-schac-home to OIN."
  [oin-mapper-config]
  (let [mapping (read-mapping oin-mapper-config)]
    (fn oin-mapper [institution-schac-home]
      (or (get mapping institution-schac-home)
          (throw (ex-info "Cannot find valid mapping for institution-schac-home"
                          {:institution-schac-home institution-schac-home}))))))

(defn institution-schac-homes
  "Collection of all configured institution-schac-homes."
  [oin-mapper-config]
  (-> oin-mapper-config
      (read-mapping)
      (keys)))

(defn wrap-oin-mapper
  [f oin-mapper]
  (fn with-oin-mapper
    [{:keys [institution-schac-home] :as request}]
    (f (assoc request :institution-oin (oin-mapper institution-schac-home)))))
