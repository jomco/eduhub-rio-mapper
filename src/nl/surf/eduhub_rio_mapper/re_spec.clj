(ns nl.surf.eduhub-rio-mapper.re-spec
  "Define a spec with a generator for a regular expression."
  (:require [clojure.spec.alpha :as s]
            [miner.strgen :as strgen]))

(defmacro re-spec
  "Defines a spec with a generator for regular expression `re`.

  This is a macro and not a function, because using a macro will print
  the literal regular expression used in spec failures."
  [re]
  `(s/spec (s/and string? #(re-matches ~re %))
           :gen #(strgen/string-generator ~re)))

(defn looks-like-html?
  "Test if a text string contains HTML constructs."
  [s]
  (re-find #"(<(\S|\Z))|(&\S+;)" s))

(defn text-spec
  "Define a string spec with a minimum and maximum length.

  Also ensures that the string does not contain any text sequences
  that are considered invalid by the RIO API (meaning, we disallow
  anything that looks like HTML tags or escape codes)"
  [min-length max-length]
  (s/spec (s/and string?
                 #(<= min-length (count %) max-length)
                 #(not (looks-like-html? %)))))
