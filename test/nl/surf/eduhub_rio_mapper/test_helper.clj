(ns nl.surf.eduhub-rio-mapper.test-helper
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer :all]))

(defn load-json [path]
  (some-> path
          io/resource
          slurp
          (json/read-str :key-fn keyword)))

(defn wait-while-predicate [predicate val-atom max-sec]
  (loop [ttl (* max-sec 10)]
    (when (and (pos? ttl) (predicate @val-atom))
      (Thread/sleep 100)
      (recur (dec ttl)))))

(defn wait-for-expected [expected val-atom max-sec]
  (wait-while-predicate #(not= % expected) val-atom max-sec)
  (is (= expected @val-atom)))
