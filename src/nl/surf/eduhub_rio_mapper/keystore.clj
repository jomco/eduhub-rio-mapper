(ns nl.surf.eduhub-rio-mapper.keystore
  (:require [clojure.java.io :as io])
  (:import (java.security KeyStore
                          KeyStore$PasswordProtection
                          KeyStore$PrivateKeyEntry)))

(defn keystore
  ^KeyStore [path password]
  (with-open [in (io/input-stream path)]
    (doto (KeyStore/getInstance "JKS")
      (.load in (char-array password)))))

(defn- get-entry
  ^KeyStore$PrivateKeyEntry [^KeyStore keystore alias password]
  (.getEntry keystore
             alias
             (KeyStore$PasswordProtection. (char-array password))))

(defn get-key
  [^KeyStore keystore alias password]
  (.getKey keystore alias (char-array password)))

(defn get-certificate
  [^KeyStore keystore alias password]
  (-> keystore
      (get-entry alias password)
      .getCertificate
      .getEncoded))
