(ns nl.surf.eduhub-rio-mapper.rio
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.rio.OpleidingsEenheid :as-alias OpleidingsEenheid]))

(s/def ::OpleidingsEenheid/beginDatum string?)
(s/def ::OpleidingsEenheid/eindDatum string?)
(s/def ::OpleidingsEenheid/soort #{"VARIANT" "OPLEIDING"})
(s/def ::type #{::HoOpleiding})

(defn rio-type-pred
  [type]
  #(= (::type %) type))

(s/def ::HoOpleiding
  (s/and (s/keys :req [::type
                       ::OpleidingsEenheid/beginDatum
                       ::OpleidingsEenheid/soort]
                 :opt [::OpleidingsEenheid/eindDatum])
         (rio-type-pred ::HoOpleiding)))
