(ns nl.surf.eduhub-rio-mapper.e2e-helper
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as spec]
            [clojure.string :as str]
            [clojure.test :as test]
            [clojure.xml :as xml]
            [environ.core :refer [env]]
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
;; - redis (docker run --name redis-rio-mapper -p 6379:6379 -d redis:7-alpine)
;; - rio test/dev environment access (KIT? KAT?)
;; - schac home of dev client (see test-clients.json)
;; - conext account to access local mapper api
;; - application added to gateway to access test entities



(def ^:private last-seen-testing-contexts (atom nil))

(defn- print-testing-contexts
  "Print eye catching testing context (when it's not already printed)."
  []
  (let [testing-contexts (test/testing-contexts-str)]
    (when-not (= @last-seen-testing-contexts
                 testing-contexts)
      (reset! last-seen-testing-contexts testing-contexts)
      (println "\n###\n###" testing-contexts "\n###\n"))))

(defmacro print-boxed
  "Print pretty box around output of evaluating `form`."
  [title & form]
  `(let [s# (with-out-str (do ~@form))]
     (print-testing-contexts)
     (print "╭─────" ~title "\n│ ")
     (println (str/replace (str/trim s#) #"\n" "\n│ "))
     (println "╰─────")))

(defn- print-soap-body
  "Print the body of a SOAP request or response."
  [s]
  (let [xml (xml/parse (ByteArrayInputStream. (.getBytes s)))]
    (xml-utils/pretty-print-xml (-> xml :content second :content first)
                                :initial-indent "  ")))

(defn- print-json
  "Print indented JSON."
  [v]
  (when v
    (print "  ")
    (println (json/write-str v :indent true :indent-depth 1))))

(defn- print-json-str
  "Parse string as JSON and print it."
  [s]
  (when s
    (print-json (json/read-str s))))

(defn- print-api-message
  "Print boxed API request and response."
  [{{:keys [method url]}  :req
    {:keys [status body]} :res}]
  (print-boxed
      "API"
    (println (str/upper-case (name method)) url status)
    (when body
      (print-json body))))

(defn- print-rio-message
  "Print boxed RIO request and response."
  [{{:keys                [method url]
     req-body             :body
     {action :SOAPAction} :headers} :req
    {res-body :body
     :keys    [status]}             :res}]
  (print-boxed
      "RIO"
    (println (str/upper-case method) url status)
    (println "- action:" action)
    (println "- request:\n")
    (print-soap-body req-body)
    (println)
    (when (= http-status/ok status)
      (println "- response:\n")
      (print-soap-body res-body)
      (println))))

(defn- print-ooapi-message
  "Print boxed OOAPI request and response."
  [{{:keys [method url]}  :req
    {:keys [status body]} :res}]
  (print-boxed
      "OOAPI"
    (println (str/upper-case method) url status)
    (println)
    (when (= http-status/ok status)
      (print-json-str body))))

(defn- print-http-messages
  "Print HTTP message as returned by API status."
  [http-messages]
  (when-let [{:keys [req] :as msg} (first http-messages)]
    (if (and (= "post" (:method req))
             (-> req :headers :SOAPAction))
      (print-rio-message msg)
      (print-ooapi-message msg))
    (recur (next http-messages))))



;; Defer running make-config so running some (other!) tests is still
;; possible when environment incomplete.
(def config (memoize #(cli/make-config)))

(def base-url
  (memoize
   #(str "http://"
         (-> (config) :api-config :host)
         ":"
         (-> (config) :api-config :port))))

(defn- encode-base64
  "Base64 bytes of given string."
  [s]
  (str/join (map char (.encode (Base64/getEncoder) ^bytes (.getBytes s)))))

(def ^:private bearer-token
  ;; A conext bearer token expires in 3600 seconds (1 hour), should be
  ;; plenty of time to run all e2e tests..
  (future
    (let [{:keys [client-id
                  client-secret
                  token-endpoint]} env]
      (-> {:method       :post
           :url          token-endpoint
           :content-type :x-www-form-urlencoded
           :query-params {"grant_type" "client_credentials"
                          "audience"   client-id}

           :headers {"Authorization" (str "Basic "
                                          (encode-base64 (str client-id
                                                              ":"
                                                              client-secret)))}
           :as      :json}
          (http/request)
          (get-in [:body :access_token])))))

(defn- api-path
  "Returns path for given API action."
  [action args]
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

(defn- api
  "Make API call, print results and http-message, and return response."
  [method action args]
  (let [url           (str (base-url) (api-path action args))
        req           {:method           method
                       :url              url
                       :headers          {"Authorization" (str "Bearer " @bearer-token)}
                       :query-params     {:http-messages "true"}
                       :as               :json
                       :throw-exceptions false}
        res           (http/request req)
        http-messages (-> res :body :http-messages)
        res           (if (map? (:body res)) ;; expect JSON response but can be something else on error
                        (update res :body dissoc :http-messages)
                        res)]
    (print-api-message {:req req, :res res})
    (print-http-messages http-messages)
    res))

(defn- api-token-status-final?
  "Determine if polling can be stopped from API status call response."
  [res]
  (cli/final-status? (-> res :body :status keyword)))

(def job-status-poll-sleep-msecs 500)
(def job-status-poll-total-msecs 60000)

(defn post-job
  "Post a job through the API.
  Return the HTTP response of the call and includes a future to access
  the job result at `:result-future`."
  [action & args]
  (let [{:keys [status] {:keys [token]} :body :as res} (api :post action args)]
    (assoc res :result-future
           (future
             (if (= http-status/ok status)
               (loop [tries-left (/ job-status-poll-total-msecs
                                    job-status-poll-sleep-msecs)]
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

(defn job-result
  "Use `get-in` to access job response from `post-job`."
  [job & ks]
  (get-in @(:result-future job) ks))

(defn job-result-status
  "Short cut to `post-job` job response status."
  [job]
  (job-result job :status))

(defn job-result-attributes
  "Short cut `get-in` to `post-job` job response attributes."
  [job & ks]
  (apply job-result job :attributes ks))

(defn job-result-opleidingseenheidcode
  "Short cut to `post-job` job response attributes opleidingseenheidcode."
  [job]
  (job-result-attributes job :opleidingseenheidcode))

(defn has-diffs?
  "Returns `true` if \"diff\" is detected in given attributes."
  [attributes]
  (->> attributes
       (map #(:diff (val %)))
       (filter (partial = true))
       seq))



(defn ooapi
  "Get OOAPI UUID of automatically uploaded fixture."
  [name]
  (get remote-entities/*session* name))



(def ^:private rio-getter
  (memoize #(rio-loader/make-getter (:rio-config (config)))))
(def ^:private client-info
  (memoize #(clients-info/client-info (:clients (config))
                                      (:client-id env))))

(defn rio-relations
  "Call RIO `opleidingsrelaties-bij-opleidingseenheid`."
  [code]
  {:pre [(spec/valid? ::rio/opleidingscode code)]}
  ((rio-getter) {::rio/type           rio-loader/opleidingsrelaties-bij-opleidingseenheid
                 ::rio/opleidingscode code
                 :institution-oin     (:institution-oin (client-info))}))

(defn rio-has-relation?
  "Fetch relations of `rio-child` and test if it includes `rio-parent`."
  [rio-parent rio-child]
  {:pre [(spec/valid? ::rio/opleidingscode rio-parent)
         (spec/valid? ::rio/opleidingscode rio-child)]}
  (let [relations (rio-relations rio-child)]
    (some #(contains? (:opleidingseenheidcodes %) rio-parent)
          relations)))



(defonce ^:private serve-api-process-atom (atom nil))
(defonce ^:private worker-process-atom (atom nil))

(def wait-for-serve-api-sleep-msec 500)
(def wait-for-serve-api-total-msec 20000)

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
      (loop [tries-left (/ wait-for-serve-api-total-msec
                           wait-for-serve-api-sleep-msec)]
        (when (neg? tries-left)
          (throw (ex-info "Failed to start serve-api"
                          {:msecs wait-for-serve-api-total-msec})))
        (let [result
              (try
                (http/get (str (base-url) "/metrics")
                          {:throw-exceptions false})
                true
                (catch java.net.ConnectException _
                  false))]
          (when-not result
            (Thread/sleep wait-for-serve-api-sleep-msec)
            (recur (dec tries-left))))))

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
