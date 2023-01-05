;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2023 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.rio.opleidingseenheid-finder
  (:require
    [clj-time.core :as time]
    [clj-time.format :as f]
    [clojure.data.xml :as clj-xml]
    [clojure.spec.alpha :as s]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.soap :as soap]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(defn- kenmerk-content [xmlspec naam kenmerk-type]
  (xml-utils/find-in-xmlseq xmlspec #(let [[n v] (:content %)]
                             (and (= :kenmerken (:tag %))
                                  (= :kenmerknaam (:tag n))
                                  (= naam (first (:content n)))
                                  (= kenmerk-type (:tag v))
                                  (first (:content v))))))

(defn- nr-pages [xmlseq]
  (when-let [element (xml-utils/find-in-xmlseq xmlseq #(and (= :aantalPaginas (:tag %)) %))]
    (Integer/parseInt (first (:content element)))))

(defn- matching-opleidingseenheid? [opl-eenh rio-code]
  (xml-utils/find-in-xmlseq (xml-seq opl-eenh) #(and (= :opleidingseenheidcode (:tag %))
                                           (= rio-code (first (:content %)))
                                           %)))

(def opleidingseenheid-namen #{:hoOpleiding :particuliereOpleiding :hoOnderwijseenhedencluster :hoOnderwijseenheid})
(def opleidingseenheidperiode-namen #{:hoOpleidingPeriode :particuliereOpleidingPeriode :hoOnderwijseenhedenclusterPeriode :hoOnderwijseenheidPeriode})

(defn- find-opleidingseenheid-in-response [xmlseq rio-code]
  (xml-utils/find-in-xmlseq xmlseq #(and (opleidingseenheid-namen (:tag %))
                               (matching-opleidingseenheid? % rio-code)
                               %)))

(def opvragen-opleidingseenheden-soap-action (str "opvragen_" rio.loader/opleidingseenheden-van-organisatie))
(def opvragen-opleidingseenheden-response-tagname (str "ns2:" opvragen-opleidingseenheden-soap-action "_response"))

(defn- make-finder [onderwijsbestuurcode institution-oin {:keys [read-url credentials recipient-oin] :as _config}]
  {:pre [institution-oin recipient-oin (not= institution-oin recipient-oin)
         (s/valid? ::common/onderwijsbestuurcode onderwijsbestuurcode)]}
  (fn [page]
    (let [soap-req (soap/prepare-soap-call opvragen-opleidingseenheden-soap-action
                                          [[:duo:onderwijsbestuurcode onderwijsbestuurcode]
                                           [:duo:pagina page]]
                                          (rio.loader/make-datamap institution-oin recipient-oin)
                                          credentials)
          request  (assoc credentials
                     :url read-url
                     :method :post
                     :body soap-req
                     :headers {"SOAPAction" (str rio.loader/contract "/" opvragen-opleidingseenheden-soap-action)}
                     :content-type :xml)]
      (-> request
          http-utils/send-http-request
          (rio.loader/guard-getter-response type opvragen-opleidingseenheden-response-tagname)))))

(defn extract-period-data [xmlseq]
  (->> (:content xmlseq)
       (filter #(#{:begindatum :einddatum :naamKort :naamLang :omschrijving :internationaleNaam} (:tag %)))
       (map (fn [e] {(:tag e) (-> e :content first)}))
       (into {})))

(defn summarize-opleidingseenheid [opleidingseenheid]
  (let [ooapi-id (kenmerk-content (xml-seq opleidingseenheid) "eigenOpleidingseenheidSleutel" :kenmerkwaardeTekst)
        period-data (map extract-period-data (xml-utils/find-all-in-xmlseq (xml-seq opleidingseenheid)
                                                                 #(and (opleidingseenheidperiode-namen (:tag %))
                                                                       %)))
        current-period (->> period-data
                            (sort-by :begindatum)
                            (filter #(= -1 (compare (:begindatum %) (f/unparse (f/formatter "yyyy-MM-dd") (time/now)))))
                            last)]
    (assoc current-period
      :eigenOpleidingseenheidSleutel ooapi-id)))

(defn find-opleidingseenheid [onderwijsbestuurcode rio-code institution-oin config]
  {:pre [(s/valid? ::common/onderwijsbestuurcode onderwijsbestuurcode)]}
  (when rio-code
    (let [finder (make-finder onderwijsbestuurcode institution-oin config)]
      (loop [page 0]
        (let [xmlseq            (-> (finder page) clj-xml/parse-str xml-seq)
              opleidingseenheid (find-opleidingseenheid-in-response xmlseq rio-code)
              has-next-page     (< page (dec (nr-pages xmlseq)))]
          (if opleidingseenheid
            opleidingseenheid
            (when has-next-page
              (recur (inc page)))))))))
