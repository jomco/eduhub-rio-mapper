(ns nl.surf.eduhub-rio-mapper.utils.exception-utils
  (:require [clojure.stacktrace :as trace]
            [clojure.string :as str]))

(defn backtrace-matches-regex? [ex regex]
  (->> ex
       trace/print-stack-trace
       with-out-str
       str/split-lines
       (filter #(re-find regex %))
       seq))
