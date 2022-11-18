(ns nl.surf.eduhub-rio-mapper.smoke-test
  (:require
    [clj-http.client :as client]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.cli :as cli]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
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

(defn- make-playbacker [idx]
  (let [count-atom (atom 0)
        dir        (numbered-file "test/fixtures/smoke" idx)]
    (fn [actual-request]
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

(defn- load-relations [getter client-info code]
  {:pre [code]}
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

(defn req-name [request]
  (let [action (get-in request [:headers "SOAPAction"])]
    (if action
      (last (str/split action #"/"))
      (-> request :url
          (subs (count "https://gateway.test.surfeduhub.nl/"))
          (str/replace \/ \-)
          (str/split #"\?")
          first))))

(defn- make-recorder [idx desc]
  (let [mycounter (atom 0)]
    (fn [handler request]
      (let [response (handler request)
            counter  (swap! mycounter inc)]
        (let [file-name (str "test/fixtures/smoke/" idx "-" desc "/" counter "-" (req-name request) ".edn")]
          (io/make-parents file-name)
          (spit file-name
                (prn-str {:request  (select-keys request [:method :headers :url :body])
                          :response (select-keys response [:status :body])})))
        response))))

(deftest smoketest
  (let [recording?        false
        client-id         "rio-mapper-dev.jomco.nl"
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09cbcf5"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd6596d"
        course-id         "8fca6e9e-4eb6-43da-9e78-4e1fad29abf0"
        code              (atom nil)                        ; During the tests we'll learn which opleidingscode we should use.
        config            (cli/make-config)
        handlers          (cli/make-handlers config)
        client-info       (clients-info/client-info (:clients config) client-id)
        runner            (make-runner handlers client-info)
        goedgekeurd?      #(= "true" (-> % vals first :requestGoedgekeurd))
        commands          [[1 "upsert-eduspec" goedgekeurd? #(runner "education-specification" eduspec-parent-id "upsert")]
                           [2 "upsert-eduspec" goedgekeurd? #(runner "education-specification" eduspec-child-id "upsert")]
                           [3 "get-relations" identity #(load-relations (:getter handlers) client-info @code)]
                           [4 "delete-eduspec" goedgekeurd? #(runner "education-specification" eduspec-child-id "delete")]
                           [5 "get-relations" nil? #(load-relations (:getter handlers) client-info @code)]
                           [6 "upsert-course" goedgekeurd? #(runner "course" course-id "upsert")]
                           [7 "delete-course" goedgekeurd? #(runner "course" course-id "delete")]
                           [8 "delete-eduspec" goedgekeurd? #(runner "education-specification" eduspec-parent-id "delete")]]]
    (doseq [[idx desc pred cmd] commands]
      (let [test-fn (fn []
                      (let [result (cmd)]
                        (when-let [opleidingscode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
                          (swap! code #(if (nil? %) opleidingscode %)))
                        (is (pred result) (str desc idx))))]
        (if recording?
          (binding [http-utils/recorder (make-recorder idx desc)]
            (test-fn))
          (binding [client/request (make-playbacker idx)]
            (test-fn)))))))
