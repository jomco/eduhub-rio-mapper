(ns nl.surf.eduhub-rio-mapper.redis
  (:require [clojure.string :refer [upper-case]]
            [taoensso.carmine :as car :refer [wcar]]
            [taoensso.carmine.commands :as carcmd])
  (:refer-clojure :exclude [get keys set]))

(defmacro defcmd [n]
  (let [cmd (intern 'taoensso.carmine n)]
    `(defn ~n [conn# & args#]
       (wcar conn# (apply ~cmd args#)))))

(defcmd del)
(defcmd get)
(defcmd keys)
(defcmd lpop)
(defcmd lpush)
(defcmd lrange)
(defcmd rpush)
(defcmd set)

(defmacro defcmd+ [n]
  `(let [cmd# (with-meta
               (fn [& x#]
                 (carcmd/enqueue-request 1 (into [~(upper-case (name n))] x#)))
               {:redis-api true})]
     (defn ~n [conn# & args#]
       (wcar conn# (apply cmd# args#)))))

(defcmd+ lmove) ; since redis 6.2.0
