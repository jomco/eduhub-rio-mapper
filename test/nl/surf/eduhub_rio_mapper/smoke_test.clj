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
    [nl.surf.eduhub-rio-mapper.dry-run :as dry-run]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.processing :as processing]
    [nl.surf.eduhub-rio-mapper.rio :as rio]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader])
  (:import [clojure.lang ExceptionInfo]
           [java.io PushbackReader]))

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

(defn- make-vcr [method]
  (case method
    :playback make-playbacker
    :record   make-recorder))

(deftest smoketest
  (let [vcr               (make-vcr :playback)
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09c0004"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd60002"
        program-id        "49ca7998-74b1-f44a-1ec1-000000000002"
        config            (cli/make-config)
        handlers          (processing/make-handlers config)
        client-info       (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        logging-runner    (make-runner handlers
                                       client-info
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
                           [6 "upsert" :program  program-id        goedgekeurd?]
                           [7 "delete" :program  program-id        goedgekeurd?]
                           [8 "delete" :eduspec  eduspec-parent-id goedgekeurd?]
                           [9 "upsert" :program  program-id        #(= (-> % :errors :message)
                                                                       (str "No education specification found with id: " eduspec-parent-id))]]]
    ;; Test resolve giving an error
    (let [[idx action ootype id] [20 "upsert" :eduspec  eduspec-parent-id]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-" (name ootype)))]
          (let [result        (logging-runner ootype id action)]
            (is (= {:phase :resolving, :retryable? false} (select-keys (:errors result) [:phase :retryable?])))))))

    ;; Test filter out AFGELEID_VAN relaties
    (let [[idx action] [40 "get"]]
      (testing (str "Command " idx " relation 1234O4321")
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-relation"))]
          (let [result        (load-relations (:getter handlers) client-info "1234O4321")]
            (is (empty? result))))))

    (testing "Test upsert program"
      (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" 6 "upsert-program")]
        (let [result (logging-runner :program program-id "upsert")
              upsert (get-in result [:http-messages 5 :req])]
          (is (= 7 (count (:http-messages result))))
          (is (str/includes? (:body upsert) "<duo:aangebodenOpleidingCode>49ca7998-74b1-f44a-1ec1-000000000002</duo:aangebodenOpleidingCode>")))))

    ;; Test with http message logging enabled
    (let [[idx action ootype id pred?] [1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-relation" (name ootype)))]
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

    (let [[idx action ootype id] [30 "upsert" :eduspec  eduspec-child-id]]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-" (name ootype)))]
          (let [result        (logging-runner ootype id action)
                http-messages (:http-messages result)]
            (is (= "http://duo.nl/contract/DUO_RIO_Beheren_OnderwijsOrganisatie_V4/verwijderen_opleidingsrelatie"
                   (get-in http-messages [7 :req :headers "SOAPAction"])))))))

    (testing "Delete program using code not id"
      (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" 7 "delete-program")]
        (let [result  (logging-runner :program program-id "delete")
              body    (get-in result [:http-messages 1 :req :body])
              ao-code "49ca7998-74b1-f44a-1ec1-888800000002"]
          ;; ao-code is returned by the resolve action - it should be used in the "verwijder" action,
          ;; not id.
          (is (str/includes? body ao-code)))))

    (doseq [[idx action ootype id pred?] commands]
      (testing (str "Command " idx " " action " " id)
        (binding [http-utils/*vcr* (vcr "test/fixtures/smoke" idx (str action "-" (name ootype)))]
         (let [result  (runner ootype id action)
               http-messages (:http-messages result)
               oplcode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
           (when oplcode (swap! code #(if (nil? %) oplcode %)))
           (is (nil? http-messages))
           (is (pred? result) (str action "-" (name ootype) idx))))))))

(deftest opleidingseenheid-finder-test
  (let [vcr    (make-vcr :playback)
        config (cli/make-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)
        handlers (processing/make-handlers {:rio-config rio-config
                                            :gateway-root-url (:gateway-root-url config)
                                            :gateway-credentials (:gateway-credentials config)})]
    (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-finder" 1 "finder")]
      (let [result (rio.loader/find-opleidingseenheid "1010O3664" (:getter handlers) (:institution-oin client-info))]
        (is (some? result))))))

(deftest dryrun-test
  (let [vcr    (make-vcr :playback)
        config (cli/make-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)
        handlers (processing/make-handlers {:rio-config rio-config
                                            :gateway-root-url (:gateway-root-url config)
                                            :gateway-credentials (:gateway-credentials config)})
        dry-run! (:dry-run! handlers)]

    (testing "education-specifications"
      (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-dryrun" 1 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "afb435cc-5352-f55f-a548-41c9dfd60002"
                                      ::ooapi/type "education-specification"))]
          (is (some? result))
          (is (= {:begindatum {:diff false},
                  :eigenOpleidingseenheidSleutel {:diff false},
                  :omschrijving {:diff false},
                  :naamLang {:diff false},
                  :naamKort {:diff false},
                  :internationaleNaam {:diff false},
                  :status "found",
                  :opleidingseenheidcode "1010O8815"}
                 (:dry-run result))))))

    (testing "education-specifications"
      (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-dryrun" 2 "finder")]
        (let [result (dry-run! (assoc client-info
                                 ::ooapi/id "9338214e-3978-484c-676d-427303a92748"
                                 ::ooapi/type "education-specification"))]
          (is (some? result))
          (is (= {:begindatum {:diff false},
                  :eigenOpleidingseenheidSleutel {:diff false},
                  :omschrijving {:diff false},
                  :naamLang {:diff false},
                  :naamKort {:diff false},
                  :internationaleNaam {:diff false},
                  :status "found",
                  :opleidingseenheidcode "1011O3434"}
                 (:dry-run result))))))

    (testing "courses"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-dryrun" 1 "finder")]
        (let [result (dry-run! (assoc client-info
                                 ::ooapi/id "4c358c84-dfc3-4a30-874e-0b70db15638b"
                                 ::ooapi/type "course"))]
          (is (= {:eigenNaamInternationaal {:diff false},
                  :eigenNaamAangebodenOpleiding {:diff false},
                  :cohorten
                  {:diff true,
                   :current [],
                   :proposed
                   [{:cohortcode "aeb74fae-0dbe-9611-addd-32be49f47d81",
                     :beginAanmeldperiode "2018-09-05",
                     :eindeAanmeldperiode "2019-01-30"}
                    {:cohortcode "ea7d7413-f342-9007-2832-69d2d58932a6",
                     :beginAanmeldperiode "2019-09-05",
                     :eindeAanmeldperiode "2020-08-30"}]},
                  :eigenOmschrijving {:diff false},
                  :onderwijsaanbiedercode {:diff false},
                  :onderwijslocatiecode {:diff false},
                  :aangebodenOpleidingCode
                  "4c358c84-dfc3-4a30-874e-0b70db15638b",
                  :status "found"}
                 (:dry-run result))))))

    (testing "course not in ooapi"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-dryrun" 2 "finder")]
        (let [result (dry-run! (assoc client-info
                                 ::ooapi/id "44444444-dfc3-4a30-874e-0b70db15638a"
                                 ::ooapi/type "course"))]
          (is (= {:status "not-found"}
                 (:dry-run result))))))

    (testing "courses"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-dryrun" 3 "finder")]
        (let [result (dry-run! (assoc client-info
                                 ::ooapi/id "4c358c84-dfc3-4a30-874e-0b70db15638b"
                                 ::ooapi/type "course"))]
          (is (= {:eigenNaamInternationaal {:diff false},
                  :eigenNaamAangebodenOpleiding {:diff false},
                  :cohorten
                  {:diff true,
                   :current [],
                   :proposed
                   [{:cohortcode "aeb74fae-0dbe-9611-addd-32be49f47d81",
                     :beginAanmeldperiode "2018-09-05",
                     :eindeAanmeldperiode "2019-01-30"}
                    {:cohortcode "ea7d7413-f342-9007-2832-69d2d58932a6",
                     :beginAanmeldperiode "2019-09-05",
                     :eindeAanmeldperiode "2020-08-30"}]},
                  :eigenOmschrijving {:diff false},
                  :onderwijsaanbiedercode {:diff false},
                  :onderwijslocatiecode {:diff false},
                  :aangebodenOpleidingCode
                  "4c358c84-dfc3-4a30-874e-0b70db15638b",
                  :status "found"}
                 (:dry-run result))))))

    (testing "course not in RIO"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-dryrun" 4 "finder")]
        (let [result (dry-run! (assoc client-info
                                 ::ooapi/id "4c358c84-dfc3-4a30-874e-0b70db15638b"
                                 ::ooapi/type "course"))]
          (is (= {:eigenNaamInternationaal
                  {:diff true,
                   :current nil,
                   :proposed "EN TRANSLATION: Micro Biotechnologie"},
                  :eigenNaamAangebodenOpleiding
                  {:diff true,
                   :current nil,
                   :proposed "NL VERTALING: Micro Biotechnologie"},
                  :cohorten
                  {:diff true,
                   :current nil,
                   :proposed
                   [{:cohortcode "aeb74fae-0dbe-9611-addd-32be49f47d81",
                     :beginAanmeldperiode "2018-09-05",
                     :eindeAanmeldperiode "2019-01-30"}
                    {:cohortcode "ea7d7413-f342-9007-2832-69d2d58932a6",
                     :beginAanmeldperiode "2019-09-05",
                     :eindeAanmeldperiode "2020-08-30"}]},
                  :eigenOmschrijving
                  {:diff true,
                   :current nil,
                   :proposed
                   "NL VERTALING: . ridge, lasso, elastic net) and Bayesian statistics. In many practical aspects of physiology in to planning and design approaches (amongst which are discussed in this industry, it’s important to evaluate alternative processes.Working group sessions:Design and evaluation of sustainability are approached both in scope and scale, quantization of energies, driving forces, change and rural development processes."},
                  :onderwijsaanbiedercode
                  {:diff true, :current nil, :proposed "110A133"},
                  :onderwijslocatiecode
                  {:diff true, :current nil, :proposed "107X215"},
                  :aangebodenOpleidingCode nil,
                  :status "found"}
                 (:dry-run result))))))))

(deftest link-test
  (let [vcr    (make-vcr :playback)
        config (cli/make-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)
        handlers (processing/make-handlers {:rio-config rio-config
                                            :gateway-root-url (:gateway-root-url config)
                                            :gateway-credentials (:gateway-credentials config)})
        link! (:link! handlers)]

    (testing "education-specifications"
      (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-link" 1 "linker")]
        (let [result (link! (assoc client-info
                              ::ooapi/id "11111112-dfc3-4a30-874e-000000000001"
                              ::ooapi/type "education-specification"
                              ::rio/opleidingscode "1010O6466"))]
          (is (= [:duo:opleidingseenheidcode "1010O6466"]
                 (get-in result [:rio-sexp 0 1])))
          (is (= {:eigenOpleidingseenheidSleutel {:diff true, :old-id "11111111-dfc3-4a30-874e-000000000001", :new-id "11111112-dfc3-4a30-874e-000000000001"}}
                 (:link result))))))

    (testing "education-specifications without opleidingseenheidsleutel"
      (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-link" 2 "linker")]
        (let [{:keys [link]}
              (link! (assoc client-info
                       ::ooapi/id "11111112-dfc3-4a30-874e-000000000001"
                       ::ooapi/type "education-specification"
                       ::rio/opleidingscode "1010O6466"))]
          (is (= {:eigenOpleidingseenheidSleutel
                  {:diff true,
                   :old-id nil,
                   :new-id "11111112-dfc3-4a30-874e-000000000001"}}
                 link)))))

    (testing "unlink education-specifications without opleidingseenheidsleutel"
      (binding [http-utils/*vcr* (vcr "test/fixtures/opleenh-link" 3 "linker")]
        (let [{:keys [link rio-sexp]}
              (link! (assoc client-info
                       ::ooapi/type "education-specification"
                       ::rio/opleidingscode "1011O3504"))]
          (is (empty? (filter #(and (sequential? %) (= :duo:kenmerken (first %)))
                              (first rio-sexp))))
          (is (= {:eigenOpleidingseenheidSleutel
                  {:diff true,
                   :old-id "b1091a8c-5352-f55f-a548-41c9dfd60001",
                   :new-id nil}}
                 link)))))

    (testing "courses"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-link" 1 "linker")]
        (let [result (link! (assoc client-info
                                 ::ooapi/id "11111111-dfc3-4a30-874e-000000000001"
                                 ::ooapi/type "course"
                                 ::rio/aangeboden-opleiding-code "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672"))]
          (is (= {:link {:eigenAangebodenOpleidingSleutel {:diff true, :old-id nil, :new-id "11111111-dfc3-4a30-874e-000000000001"}}}
                 (select-keys result [:link]))))))

    (testing "program"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-link" 2 "linker")]
        (let [result (link! (assoc client-info
                              ::ooapi/id "11111111-dfc3-4a30-874e-000000000002"
                              ::ooapi/type "program"
                              ::rio/aangeboden-opleiding-code "ab7431c0-f985-4742-aa68-42060570b17e"))]
          (is (= {:link {:eigenAangebodenOpleidingSleutel {:diff true, :old-id nil, :new-id "11111111-dfc3-4a30-874e-000000000002"}}}
                 (select-keys result [:link]))))))

    (testing "missing program"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangebodenopl-link" 3 "linker")]
        (let [request (assoc client-info
                        ::ooapi/id "11111111-dfc3-4a30-874e-000000000002"
                        ::ooapi/type "program"
                        ::rio/aangeboden-opleiding-code "00000000-d8e8-4868-b451-157180ab0001")]
          (is (thrown-with-msg? ExceptionInfo #"404 Not Found" (link! request))))))))

(deftest generate-diff-ooapi-rio-test
  (testing "normal case"
    (let [eduspec-id    "fddec347-8ca1-c991-8d39-9a85d09c0001"
          rio-summary   {:begindatum                    "1950-09-20",
                         :naamLang                      "NL VERTALING: Toetsdeskundige",
                         :naamKort                      "1T",
                         :internationaleNaam            "EN VERTALING: Toetsdeskundige",
                         :omschrijving                  "NL VERTALING: There is a 12 credits course which offers student the opportunity to experience in the domain will be paid to the depletion of fossil resources to biomass resources for energy, raw materials and tree form. The course addresses the question if and how this relates to material struggles over natural resources and its relationship to economic and technological domains;- solving optimization problems is climate change. Within a theoretical stance to support making conscious study and career choices.",
                         :eigenOpleidingseenheidSleutel eduspec-id}
          eduspec       (-> "fixtures/ooapi/education-specification-diff.json"
                            io/resource
                            slurp
                            (json/read-str :key-fn keyword))
          ooapi-summary (dry-run/summarize-eduspec eduspec)
          diff          (dry-run/generate-diff-ooapi-rio {:rio-summary rio-summary :ooapi-summary ooapi-summary})]
      (is (= {:begindatum                    {:diff true, :current "1950-09-20", :proposed "2019-08-24"},
              :eigenOpleidingseenheidSleutel {:diff false},
              :status                        "found",
              :omschrijving                  {:diff false},
              :naamLang                      {:diff true, :current "NL VERTALING: Toetsdeskundige", :proposed "Bachelor Chemische technologie"},
              :naamKort                      {:diff true, :current "1T", :proposed "B Scheikundige Technologie"},
              :internationaleNaam            {:diff true, :current "EN VERTALING: Toetsdeskundige", :proposed "Bachelor Chemical technology"}}
             (merge diff {:status "found"})))))
  (testing "no rio object"
    (let [rio-summary   nil
          eduspec       (-> "fixtures/ooapi/education-specification-diff.json"
                            io/resource
                            slurp
                            (json/read-str :key-fn keyword))
          ooapi-summary (dry-run/summarize-eduspec eduspec)
          diff          (dry-run/generate-diff-ooapi-rio {:rio-summary rio-summary :ooapi-summary ooapi-summary})]
      (is (= {:begindatum                    {:diff true, :current nil, :proposed "2019-08-24"},
              :eigenOpleidingseenheidSleutel {:diff true, :current nil, :proposed "fddec347-8ca1-c991-8d39-9a85d09c0001"},
              :omschrijving                  {:diff true, :current nil, :proposed "NL VERTALING: There is a 12 credits course which offers student the opportunity to experience in the domain will be paid to the depletion of fossil resources to biomass resources for energy, raw materials and tree form. The course addresses the question if and how this relates to material struggles over natural resources and its relationship to economic and technological domains;- solving optimization problems is climate change. Within a theoretical stance to support making conscious study and career choices."},
              :naamLang                      {:diff true, :current nil, :proposed "Bachelor Chemische technologie"},
              :naamKort                      {:diff true, :current nil, :proposed "B Scheikundige Technologie"},
              :internationaleNaam            {:diff true, :current nil, :proposed "Bachelor Chemical technology"}}
             diff)))))

(deftest aangeboden-finder-test
  (let [vcr    (make-vcr :playback)
        config (cli/make-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)]
    (testing "found aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangeboden-finder-test" 1 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672" (:institution-oin client-info) rio-config)]
          (is (some? result)))))
    (testing "did not find aangeboden opleiding"
      (binding [http-utils/*vcr* (vcr "test/fixtures/aangeboden-finder-test" 2 "finder")]
        (let [result (rio.loader/find-aangebodenopleiding "bbbbbbbb-3f4e-49c2-a1f7-e24ae82b0673" (:institution-oin client-info) rio-config)]
          (is (nil? result)))))))
