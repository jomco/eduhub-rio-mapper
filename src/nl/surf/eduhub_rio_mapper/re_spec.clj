(ns nl.surf.eduhub-rio-mapper.re-spec
  "Define a spec with a generator for a regular expression."
  (:require [clojure.spec.alpha :as s]
            [miner.strgen :as strgen]))

(defn re-spec
  "Defines a spec with a genrator for regular expression `re`."
  [re]
  (s/spec (s/and string? #(re-matches re %))
          :gen #(strgen/string-generator re)))

(defn without-dangerous-codes?
  "Test that a text string is valid for propagating to RIO.

  From the documentation (somewhere, needs to be added to the
  generated documentation files):

  S01010 : [Wanneer er in een willekeurige melding een tekst-string
  wordt aangeleverd met daarin een '<' (of de equivalente HTML-code
  &lt;) zonder daaropvolgende witruimte, wordt de tekst als potienteel
  gevaarlijk gezien en derhalve afgekeurd.] (melding: (Resourcecontrole) 'Er is een ongeldige waarde ontvangen.')"
  [s]
  (not (re-find #"<(\S|\Z)|&lt;(\S|\Z)" s)))

(defn text-spec
  "Define a string spec with a minimum and maximum length.

  Also ensures that the string does not contain any text sequences
  that are considered invalid by the RIO API."
  [min-length max-length]
  (s/spec (s/and string?
                 #(<= min-length (count %) max-length)
                 without-dangerous-codes?)))
