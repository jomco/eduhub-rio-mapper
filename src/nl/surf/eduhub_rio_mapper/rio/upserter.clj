(ns nl.surf.eduhub-rio-mapper.rio.upserter
  (:require [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.errors :refer [when-result]]))

(defn make-upserter
  [credentials]
  (fn upsert [{:keys [action rio-sexp]}]
    (when-result [xml (soap/prepare-soap-call action [rio-sexp] soap/beheren credentials)]
      (xml-utils/post-body (:dev-url soap/beheren)
                           xml
                           soap/beheren
                           action
                           credentials))))
