#!/opt/homebrew/bin/planck

(ns eq.core
  (:require [cljs.pprint :refer [pprint]]
            [cljs.reader :as edn]
            [planck.core :as planck]
            [clojure.string :as string]))

(->> (repeatedly planck/read-line)
     (take-while identity)
     string/join
     (edn/read-string)
     (pprint))
