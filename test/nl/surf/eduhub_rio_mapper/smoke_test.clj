;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
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

(ns nl.surf.eduhub-rio-mapper.smoke-test
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.cli :as cli]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.processing :as processing]
    [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import [java.io PushbackReader]))

(defn- ls [dir-name]
  (map #(.getName %) (.listFiles (io/file dir-name))))

(defn only-one-if-any [list]
  (assert (< (count list) 2) (prn-str list))
  (first list))

(defn- numbered-file [basedir nr]
  {:post [(some? %)]}
  (let [filename (->> basedir
                      (ls)
                      (filter #(.startsWith % (str nr "-")))
                      (only-one-if-any))]
    (when-not filename (throw (ex-info (format "No recorded request found for dir %s nr %d" basedir nr) {})))
    (str basedir "/" filename)))

(defn- load-relations [getter client-info code]
  {:pre [code]}
  (getter {::rio/type           "opleidingsrelatiesBijOpleidingseenheid"
           :institution-oin     (:institution-oin client-info)
           ::rio/opleidingscode code}))

(def name-of-ootype
  {:eduspec "education-specification"
   :course  "course"
   :program "program"})

(defn- make-runner [handlers client-info http-logging-enabled]
  (fn run [ootype id action]
    (if (= ootype :relation)
      (load-relations (:getter handlers) client-info @id)
      (job/run! handlers
                (merge client-info
                       {::ooapi/id   id
                        ::ooapi/type (name-of-ootype ootype)
                        :action      action})
                http-logging-enabled))))

(defn req-name [request]
  (let [action (get-in request [:headers "SOAPAction"])]
    (if action
      (last (str/split action #"/"))
      (-> request :url
          (subs (count "https://gateway.test.surfeduhub.nl/"))
          (str/replace \/ \-)
          (str/split #"\?")
          first))))

(defn- make-playbacker [root idx _]
  (let [count-atom (atom 0)
        dir        (numbered-file root idx)]
    (fn [_ actual-request]
      (let [i                (swap! count-atom inc)
            fname            (numbered-file dir i)
            recording        (with-open [r (io/reader fname)] (edn/read (PushbackReader. r)))
            recorded-request (:request recording)]
        (doseq [property-path [[:url] [:method] [:headers "SOAPAction"]]]
          (let [expected (get-in recorded-request property-path)
                actual   (get-in actual-request property-path)]
            (is (= expected actual)
                (str "Unexpected property " (last property-path)))))
        (:response recording)))))

(defn- make-recorder [root idx desc]
  (let [mycounter (atom 0)]
    (fn [handler request]
      (let [response  (handler request)
            counter   (swap! mycounter inc)
            file-name (str root "/" idx "-" desc "/" counter "-" (req-name request) ".edn")
            headers   (select-keys (:headers request) ["SOAPAction" "X-Route"])]
        (io/make-parents file-name)
        (with-open [w (io/writer file-name)]
          (pprint {:request  (assoc (select-keys request [:method :url :body])
                               :headers headers)
                   :response (select-keys response [:status :body])}
                  w))
        response))))

(deftest smoketest
  (let [vcr               (if true make-playbacker make-recorder)
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09cbcf5"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd6596d"
        course-id         "8fca6e9e-4eb6-43da-9e78-4e1fad29abf0"
        config            (cli/make-config)
        logging-runner    (make-runner (processing/make-handlers config)
                                       (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
                                       true)
        runner            (make-runner (processing/make-handlers config)
                                       (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
                                       false)
        goedgekeurd?      #(= "true" (-> % vals first :requestGoedgekeurd))
        code              (atom nil) ; During the tests we'll learn which opleidingscode we should use.
        commands          [[1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]
                           [2 "upsert" :eduspec  eduspec-child-id  goedgekeurd?]
                           [3 "get"    :relation code              identity]
                           [4 "delete" :eduspec  eduspec-child-id  goedgekeurd?]
                           [5 "get"    :relation code              nil?]
                           [6 "upsert" :course   course-id         goedgekeurd?]
                           [7 "delete" :course   course-id         goedgekeurd?]
                           [8 "delete" :eduspec  eduspec-parent-id goedgekeurd?]
                           [9 "upsert" :course   course-id         #(= (-> % :errors :message)
                                                                       "No education specification found with id: fddec347-8ca1-c991-8d39-9a85d09cbcf5")]]]
    ;; Test with http message logging enabled
    (let [[idx action ootype id pred?] [1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-" (name ootype)))]
          (let [result        (logging-runner ootype id action)
                http-messages (:http-messages result)
                oplcode       (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
            (when oplcode (swap! code #(if (nil? %) oplcode %)))
            (when (= 1 idx)
              (is (= "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0" (some-> http-messages (nth 1 nil) :req :url)))
              (is (= 200 (some-> http-messages (nth 1 nil) :res :status))))
            (is (pred? result) (str action "-" (name ootype) idx))))))

    ;; Test with http message logging enabled
    (let [[idx action] [1 "opleidingseenhedenVanOrganisatie"]]
      (testing (str "Command " idx " " action)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke/cli" idx (str action "-eduspec"))]
          (let [args ["rio-mapper-dev.jomco.nl" action "100B490" "18"]
                result (-> (cli/process-command "get" args {:handlers (processing/make-handlers config)
                                                            :config   config})
                           json/read-str)]
            (is (= "1009O6891"
                   (get-in result ["opvragen_opleidingseenhedenVanOrganisatie_response" 5 "particuliereOpleiding" "opleidingseenheidcode"])))))))

    (doseq [[idx action ootype id pred?] commands]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-" (name ootype)))]
         (let [result  (runner ootype id action)
               http-messages (:http-messages result)
               oplcode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
           (when oplcode (swap! code #(if (nil? %) oplcode %)))
           (is (nil? http-messages))
           (is (pred? result) (str action "-" (name ootype) idx))))))))
