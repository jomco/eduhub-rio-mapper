(ns nl.surf.eduhub-rio-mapper.smoke-test
  (:require
    [clj-http.client :as client]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.cli :as cli]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.keystore :as keystore]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.rio :as rio])
  (:import [java.io PushbackReader]
           [java.net URI]))

(defn- ls [dir-name]
  (map #(.getName %) (.listFiles (io/file dir-name))))

(defn only-one-if-any [list]
  (assert (< (count list) 2) (prn-str list))
  (first list))

(defn- numbered-file [basedir nr]
  {:post [(some? %)]}
  (str basedir
       "/"
       (->> basedir
            (ls)
            (filter #(.startsWith % (str nr "-")))
            (only-one-if-any))))

(defn- make-playbacker [idx]
  (let [count-atom (atom 0)
        dir        (numbered-file "test/fixtures/smoke" idx)]
    (fn [actual-request]
      (let [i     (swap! count-atom inc)
            fname (numbered-file dir i)]
        (assert fname (str "No recorded request found for:" (pr-str actual-request)))
        (println (format "loaded file %s" fname))
        (let [{:keys [request response]} (with-open [r (io/reader fname)] (edn/read (PushbackReader. r)))]
          (when-let [action (get-in actual-request [:headers "SOAPAction"])]
            (assert (= action (get-in request [:headers "SOAPAction"]))))
          (assert (= (:url request) (:url actual-request)))
          response)))))

(defn- load-relations [getter client-info code]
  (getter {::rio/type           "opleidingsrelatiesBijOpleidingseenheid"
           :institution-oin     (:institution-oin client-info)
           ::rio/opleidingscode code}))

(defn- make-runner [handlers client-info]
  (fn run [type id action]
    (job/run! handlers
              (merge client-info
                     {::ooapi/id   id
                      ::ooapi/type type
                      :action      action}))))

(deftest smoketest
  (let [client-id         "rio-mapper-dev.jomco.nl"
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09cbcf5"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd6596d"
        course-id         "8fca6e9e-4eb6-43da-9e78-4e1fad29abf0"
        code              "1010O3228"
        clients           (clients-info/read-clients-data {:path "test/test-clients.json"})
        ;;config            {:rio-config          {:recipient-oin "00000001800866472000"
        ;;                                         :root-url      (URI. "https://vt-webservice.duo.nl:6977/RIO/services/")
        ;;                                         :credentials   (keystore/credentials "test/keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")}
        ;;                   :gateway-root-url    (URI. "https://gateway.test.surfeduhub.nl/")
        ;;                   :gateway-credentials {:password "575757575757575757575757575757", :username "rio-test"}}
        config            (cli/make-config)
        handlers          (cli/make-handlers config)
        client-info       (clients-info/client-info (:clients config) client-id)
        runner            (make-runner handlers client-info)
        goedgekeurd?      #(= "true" (-> % vals first :requestGoedgekeurd))
        commands          [[1 goedgekeurd? #(runner "education-specification" eduspec-parent-id "upsert")]
                           [2 goedgekeurd? #(runner "education-specification" eduspec-child-id "upsert")]
                           [3 identity #(load-relations (:getter handlers) client-info code)]
                           [4 goedgekeurd? #(runner "education-specification" eduspec-child-id "delete")]
                           [5 nil? #(load-relations (:getter handlers) client-info code)]
                           [6 goedgekeurd? #(runner "course" course-id "upsert")]
                           [7 goedgekeurd? #(runner "course" course-id "delete")]
                           [8 goedgekeurd? #(runner "education-specification" eduspec-parent-id "delete")]]]
    (doseq [[idx pred cmd] commands]
      (binding [client/request (make-playbacker idx)]
        (let [result (cmd)]
          (is (pred result)))))))
