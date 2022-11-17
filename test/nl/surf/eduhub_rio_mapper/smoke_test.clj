(ns nl.surf.eduhub-rio-mapper.smoke-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.cli :as cli]
    [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
    [nl.surf.eduhub-rio-mapper.http-utils :as http-utils]
    [nl.surf.eduhub-rio-mapper.job :as job]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
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

(defn- load-relations [getter client-info code]
  {:pre [code]}
  (getter {::rio/type           "opleidingsrelatiesBijOpleidingseenheid"
           :institution-oin     (:institution-oin client-info)
           ::rio/opleidingscode code}))

(def name-of-ootype
  {:eduspec "education-specification"
   :course  "course"
   :program "program"})

(defn- make-runner [handlers client-info]
  (fn run [ootype id action]
    (if (= ootype :relation)
      (load-relations (:getter handlers) client-info @id)
      (job/run! handlers
                (merge client-info
                       {::ooapi/id   id
                        ::ooapi/type (name-of-ootype ootype)
                        :action      action})))))

(defn req-name [request]
  (let [action (get-in request [:headers "SOAPAction"])]
    (if action
      (last (str/split action #"/"))
      (-> request :url
          (subs (count "https://gateway.test.surfeduhub.nl/"))
          (str/replace \/ \-)
          (str/split #"\?")
          first))))

(defn- make-playbacker [idx _]
  (let [count-atom (atom 0)
        dir        (numbered-file "test/fixtures/smoke" idx)]
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

(defn- make-recorder [idx desc]
  (let [mycounter (atom 0)]
    (fn [handler request]
      (let [response (handler request)
            counter  (swap! mycounter inc)]
        (let [file-name (str "test/fixtures/smoke/" idx "-" desc "/" counter "-" (req-name request) ".edn")
              headers   (select-keys (:headers request) ["SOAPAction" "X-Route"])]
          (io/make-parents file-name)
          (spit file-name
                (prn-str {:request  (assoc (select-keys request [:method :url :body])
                                      :headers headers)
                          :response (select-keys response [:status :body])})))
        response))))

(deftest smoketest
  (let [vcr               (if :playback make-playbacker make-recorder)
        eduspec-parent-id "fddec347-8ca1-c991-8d39-9a85d09cbcf5"
        eduspec-child-id  "afb435cc-5352-f55f-a548-41c9dfd6596d"
        course-id         "8fca6e9e-4eb6-43da-9e78-4e1fad29abf0"
        config            (cli/make-config)
        runner            (make-runner (cli/make-handlers config)
                                       (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl"))
        goedgekeurd?      #(= "true" (-> % vals first :requestGoedgekeurd))
        code              (atom nil) ; During the tests we'll learn which opleidingscode we should use.
        commands          [[1 "upsert" :eduspec  eduspec-parent-id goedgekeurd?]
                           [2 "upsert" :eduspec  eduspec-child-id  goedgekeurd?]
                           [3 "get"    :relation code              identity]
                           [4 "delete" :eduspec  eduspec-child-id  goedgekeurd?]
                           [5 "get"    :relation code              nil?]
                           [6 "upsert" :course   course-id         goedgekeurd?]
                           [7 "delete" :course   course-id         goedgekeurd?]
                           [8 "delete" :eduspec  eduspec-parent-id goedgekeurd?]]]
    (doseq [[idx action ootype id pred?] commands]
      (binding [http-utils/*vcr* (vcr idx (str action "-" (name ootype)))]
        (let [result  (runner ootype id action)
              oplcode (-> result :aanleveren_opleidingseenheid_response :opleidingseenheidcode)]
          (when oplcode (swap! code #(if (nil? %) oplcode %)))
          (is (pred? result) (str (str action "-" (name ootype)) idx)))))))
