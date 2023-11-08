(ns nl.surf.eduhub-rio-mapper.e2e-helper
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.test :as test]
            [clojure.xml :as xml]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.cli :as cli]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio-loader]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils])
  (:import (java.util Base64)
           (java.io ByteArrayInputStream)))

;; PREREQUISITES:
;; - redis; docker run --name redis-rio-mapper -p 6379:6379 -d redis:7-alpine
;; - mapper
;; - worker
;; - rio test/dev environment
;; - schac home of dev client
;; - dev client setup to access mapper
;; - dev client setup to access rio
;; - ooapi endpoint with magic uids
;; - gateway
;;   options:
;;   - fake build into ooapi endpoint
;;   - real using docker (needs some release image and config)
;;   - real using gateway at surf (needs ooapi to be public webapp)
;; STORE_HTTP_REQUESTS set?

(def job-status-poll-max 60)
(def job-status-poll-sleep-msecs 1000)

(def config (last (envopts/opts env cli/opts-spec)))

(def base-url (str "http://"
                   (-> config :api-config :host)
                   ":"
                   (-> config :api-config :port)))



(def last-seen-testing-contexts (atom nil))

(defn print-testing-contexts []
  (let [testing-contexts (test/testing-contexts-str)]
    (when-not (= @last-seen-testing-contexts
                 testing-contexts)
      (reset! last-seen-testing-contexts testing-contexts)
      (println "\n###\n###" testing-contexts "\n###\n"))))

(defmacro print-boxed [title & form]
  `(let [s# (with-out-str (do ~@form))]
     (print-testing-contexts)
     (print "╭─────" ~title "\n│ ")
     (println (s/replace (s/trim s#) #"\n" "\n│ "))
     (println "╰─────")))

(defn- print-soap-body [s]
  (let [xml (xml/parse (ByteArrayInputStream. (.getBytes s)))]
    (xml-utils/pretty-print-xml (-> xml :content second :content first)
                                :initial-indent "  ")))

(defn- print-json [v]
  (when v
    (print "  ")
    (println (json/write-str v :indent true :indent-depth 1))))

(defn- print-json-str [s]
  (when s
    (print-json (json/read-str s))))

(defn- print-api-message [{{:keys [method url]}  :req
                           {:keys [status body]} :res}]
  (print-boxed
   "API"
   (println (s/upper-case (name method)) url status)
   (when body
     (print-json body))))

(defn- print-rio-message [{{:keys                [method url]
                            req-body             :body
                            {action :SOAPAction} :headers} :req
                           {res-body :body
                            :keys    [status]}             :res}]
  (print-boxed
   "RIO"
   (println (s/upper-case method) url status)
   (println "- action:" action)
   (println "- request:\n")
   (print-soap-body req-body)
   (println)
   (when (= http-status/ok status)
     (println "- response:\n")
     (print-soap-body res-body)
     (println))))

(defn- print-ooapi-message [{{:keys [method url]}  :req
                             {:keys [status body]} :res}]
  (print-boxed
   "OOAPI"
   (println (s/upper-case method) url status)
   (println)
   (when (= http-status/ok status)
     (print-json-str body))))

(defn- print-http-messages [http-messages]
  (when-let [{:keys [req] :as msg} (first http-messages)]
    (if (and (= "post" (:method req))
             (-> req :headers :SOAPAction))
      (print-rio-message msg)
      (print-ooapi-message msg))
    (recur (next http-messages))))



(defn- encode-base64
  "Base64 bytes of given string."
  [s]
  (s/join (map char (.encode (Base64/getEncoder) ^bytes (.getBytes s)))))

(def ^:private bearer-token
  ;; conext token expires in 3600 seconds (1 hour)
  ;; TODO rename these environment vars
  (future
    (-> {:method       :post
         :url          (env :token-endpoint)
         :content-type :x-www-form-urlencoded
         :query-params {"grant_type" "client_credentials"
                        "audience"   (env :surf-conext-client-id)}

         :headers {"Authorization" (str "Basic "
                                        (encode-base64 (str (env :client-id)
                                                            ":"
                                                            (env :client-secret))))}
         :as      :json}
        (http/request)
        (get-in [:body :access_token]))))

(defn- api-path [action args]
  (case action
    :token
    (let [[token] args]
      (str "/status/" token))

    :upsert
    (let [[type ooapi-id] args]
      (str "/job/upsert/" (name type) "/" ooapi-id))

    :dry-run/upsert
    (let [[type ooapi-id] args]
      (str "/job/dry-run/upsert/" (name type) "/" ooapi-id))

    :delete
    (let [[type ooapi-id] args]
      (str "/job/delete/" (name type) "/" ooapi-id))

    :link
    (let [[rio-id type ooapi-id] args]
      (str "/job/link/" rio-id "/" (name type) "/" ooapi-id))

    :unlink
    (let [[rio-id type] args]
      (str "/job/unlink/" rio-id "/" (name type)))))

(defn- api [method action args]
  (let [url           (str base-url (api-path action args))
        req           {:method           method
                       :url              url
                       :headers          {"Authorization" (str "Bearer " @bearer-token)}
                       :query-params     {:http-messages "true"}
                       :as               :json
                       :throw-exceptions false}
        res           (http/request req)
        http-messages (-> res :body :http-messages)
        res           (update res :body dissoc :http-messages)]
    (print-api-message {:req req, :res res})
    (print-http-messages http-messages)
    res))



(defn- api-token-status-final? [res]
  (cli/final-status? (-> res :body :status keyword)))

(defn post-job [action & args]
  (let [{:keys [status] {:keys [token]} :body :as res} (api :post action args)]
    (assoc res :result-future
           (future
             (if (= http-status/ok status)
               (loop [tries-left job-status-poll-max]
                 (let [{:keys [status body] :as res} (api :get :token [token])]
                   (cond
                     (zero? tries-left)
                     (do
                       (println "\n\n⚠ too many retries on status\n")
                       ::time-out)

                     (not= http-status/ok status)
                     (do
                       (println "\n\n⚠ get token failed\n")
                       ::get-token-failed)

                     (api-token-status-final? res)
                     body

                     :else
                     (do
                       (Thread/sleep job-status-poll-sleep-msecs)
                       (recur (dec tries-left))))))
               (println "failed to post job"))))))

(defn job-result [job & ks]
  (get-in @(:result-future job) ks))

(defn job-result-status [job]
  (job-result job :status))

(defn job-result-attributes [job & ks]
  (apply job-result job :attributes ks))

(defn job-result-opleidingseenheidcode [job]
  (job-result-attributes job :opleidingseenheidcode))

(defn has-diffs?
  "Returns `true` if \"diff\" is detected in attributes."
  [attributes]
  (->> attributes
       (map #(:diff (val %)))
       (filter (partial = true))
       seq))



(defn ooapi [name]
  (get remote-entities/*session* name))



(def cli-config (cli/make-config))
(def rio-getter (rio-loader/make-getter (:rio-config cli-config)))
(def client-info (clients-info/client-info (:clients cli-config) "rio-mapper-dev.jomco.nl"))

(defn rio-relations [code]
  (rio-getter {::rio/type           rio-loader/opleidingsrelaties-bij-opleidingseenheid
               ::rio/opleidingscode code
               :institution-oin     (:institution-oin client-info)}))

(defn rio-has-relation? [rio-parent rio-child]
  (let [relations (rio-relations rio-child)]
    (some #(contains? (:opleidingseenheidcodes %) rio-parent)
          relations)))



(defonce serve-api-process-atom (atom nil))
(defonce worker-process-atom (atom nil))

(def wait-msecs 1000)
(def max-tries  20)
(def host       (-> config :api-config :host))
(def port       (-> config :api-config :port))

(defn with-running-mapper [f]
  (try
    (when-not (:mapper-url env) ;; TODO
      (reset! serve-api-process-atom
              (.exec (Runtime/getRuntime)
                     (into-array ["lein" "trampoline" "mapper" "serve-api"])))
      (reset! worker-process-atom
              (.exec (Runtime/getRuntime)
                     (into-array ["lein" "trampoline" "mapper" "worker"])))

      ;; wait for serve-api to be up and running
      (loop [n max-tries]
        (when (neg? n)
          (throw (ex-info "Failed to start serve-api"
                          {:msecs (* wait-msecs max-tries)})))
        (let [result
              (try
                (http/get (str "http://" host ":" port "/metrics")
                          {:throw-exceptions false})
                true
                (catch java.net.ConnectException _
                  false))]
          (when-not result
            (Thread/sleep wait-msecs)
            (recur (dec n))))))

    ;; run tests
    (f)

    (finally
      ;; shutdown mapper
      (when-let [proc @serve-api-process-atom]
        (.destroy proc)
        (reset! serve-api-process-atom nil))
      (when-let [proc @worker-process-atom]
        (.destroy proc)
        (reset! worker-process-atom nil)))))



;; onderwijsaanbiedercode: 110A133

;; opleidingseenheidcode: 1010O8774, 1007O8117, 1007O7374

;; onderwijslocatiecode: 107X215

;; onderwijsbestuurcode: 100B490
;; Michiel: opvragen_onderwijsaanbieder geeft een bestuurscode op
;; basis van een onderwijsaanbieder

;; lein mapper get rio-mapper-dev.jomco.nl aangebodenOpleiding 8fca6e9e-4eb6-43da-9e78-4e1fad290002 # ooapi course UID
;; lein mapper get rio-mapper-dev.jomco.nl aangebodenOpleidingenVanOrganisatie 110A133 # onderwijsaanbiedercode
