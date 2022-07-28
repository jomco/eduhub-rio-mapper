(ns nl.surf.eduhub-rio-mapper.re-spec
  "Define a spec with a generator for a regular expression."
  (:require [clojure.spec.alpha :as s]
            [miner.strgen :as strgen]))

(defn re-spec
  "Defines a spec with a genrator for regular expression `re`."
  [re]
  (s/spec (s/and string? #(re-matches re %))
          :gen #(strgen/string-generator re)))
