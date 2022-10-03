(ns nl.surf.eduhub-rio-mapper.oin-mapper
  "Translate OOAPI SchacHome of institution to OIN for RIO."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn make-oin-mapper
  [{:keys [path]}]
  (let [data    (json/read-json (io/reader path) false)
        mapping (get data "oins")]
    (when-not (seq mapping)
      (throw (ex-info "OIN Mapper configuration has no or empty \"oins\" entry." {:configuration data})))
    (fn oin-mapper [institution-schac-home]
      (or (get mapping institution-schac-home)
          (throw (ex-info "Cannot find valid mapping for insitution-id"
                          {:institution-schac-home institution-schac-home}))))))

(defn wrap-oin-mapper
  [f oin-mapper]
  (fn with-oin-mapper
    [{:keys [institution-schac-home] :as request}]
    (f (assoc request :institution-oin (oin-mapper institution-schac-home)))))
