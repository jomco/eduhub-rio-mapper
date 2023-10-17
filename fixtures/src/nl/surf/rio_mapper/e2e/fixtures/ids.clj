(ns nl.surf.rio-mapper.e2e.fixtures.ids
  "Map names to UUIDs and vice versa."
  (:import java.util.UUID))

;;  "a3b07526-a18f-10e8-937b-3404198139d8"
;;    tlow    tmid vthi vseq      node
;;
;;   4 bytes  2byt 2byt 2byt   6 bytes
;;
;;   0         1         2       x 3
;;   012345678901234567890123456789012345


(defn nameset
  [names]
  (let [names (vec (sort names))]
    {:names   names
     :indexes (into {}
                    (map-indexed (fn [i n] [n i]))
                    names)}))

(defn name?
  [{:keys [indexes] :as _nameset} n]
  (contains? indexes n))

(defn- name->index
  [nameset n]
  (or (get-in nameset [:indexes n])
      (throw (ex-info (str "Name not found: " n)
                      {:name n}))))

(defn- name->mac
  [nameset n]
  ;; convert n to index,
  ;; then index to mac address (12 digit hex string)
  (format "%012x" (name->index nameset n)))

(defn name->uuid
  [nameset session n]
  (UUID/fromString (str session (name->mac nameset n))))

(defn- uuid->index
  [u]
  (Long/parseLong (subs (str u) 24) 16))

(defn uuid->name
  [nameset u]
  (get-in nameset [:names (uuid->index u)]))

(defn mk-session
  "Create a new random session identifier"
  []
  (let [rnd (str (UUID/randomUUID))]
    (str (subs rnd 0 14)
         "1" ;; force to version 1
         (subs rnd 15 24))))

(defn uuid->session
  [u]
  (subs (str u) 0 24))
