(ns remote-entities-helper
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts])
  (:import java.util.UUID))

(def ^:private entities-resource-path
  "fixtures/remote-entities")

(defn- entities-dir
  []
  (let [dir (-> entities-resource-path
                io/resource
                io/file)]
    (when-not (and dir (.isDirectory dir))
      (throw (IllegalStateException. (str  entities-resource-path " is not a directory -- are you running in a jar?"))))
    dir))

(defn- input-resources
  []
  (->> (entities-dir)
       (.listFiles)
       (filter #(.isDirectory %))
       (remove #(string/starts-with? (.getName %) "."))
       (mapcat (fn [dir]
                 (let [dirname (.getName dir)]
                   (->> (.listFiles dir)
                        (map #(str dirname "/" (.getName %)))
                        (filter #(string/ends-with? % ".json"))))))))

(defn make-session
  "Return a new session map of entity names to random ids.

  For every json file in `entities-resource-path`, maps its dir +
  basename to a random UUID.

  So `courses/some-course.json` will be mapped as:

  \"courses/some-course\" => some-random-uuid.

  See also `with-session`"
  []
  (let [input (input-resources)]
    (into {} (map #(vector (string/replace % #".json" "") (UUID/randomUUID)) input))))

(defn- resource-path
  [n]
  (str entities-resource-path "/" n ".json"))

(defn- replace-exprs
  [templ session]
  (string/replace templ
                  #"\{\{\s*([^}\s]+)\s*}\}"
                  (fn [[_ name]]
                    (str (or (get session name)
                             (throw (ex-info (str "Can't find name '" name "'")
                                             {:name    name
                                              :session session})))))))
(defn- resource-content
  [n session]
  (-> n
      resource-path
      io/resource
      slurp
      (replace-exprs session)))

(def ^:dynamic
  *session*
  ""
  nil)

(defmacro ^:private with-session
  "Evaluate `body` with `*session*` bound to a new session.

  See also `make-session`."
  [& body]
  `(binding [*session* (make-session)]
    ~@body))

(defn entity-id
  "Return the UUID of an entity `n` in `*session*`.

  See also `with-session`"
  [n]
  {:pre [*session*]
   :post [%]}
  (get *session* n))

(def opts-spec
  {:fixtures-access-key        ["AWS/S3 Access key" :str
                                :in [:access-key]]
   :fixtures-secret-key        ["AWS/S3 Secret key" :str
                                :in [:secret-key]]
   :fixtures-endpoint-hostname ["Endpoint hostname overriding default S3 API" :str
                                :in [:endpoint-override :hostname] :default nil]
   :fixtures-endpoint-port     ["Endpoint hostname overriding default S3 API" :int
                                :in [:endpoint-override :port] :default nil]
   :fixtures-endpoint-path     ["Endpoint hostname overriding default S3 API" :str
                                :in [:endpoint-override :path] :default nil]
   :fixtures-region            ["AWS/S3 Region" :str
                                :in [:region]]
   :fixtures-bucket            ["AWS/S3 Bucket for remote entities" :str
                                :in [:bucket]]})

(defn- fixup-override-config
  [{:keys [endpoint-override region] :as config}]
  (if endpoint-override
    (-> config
        ;; if we're overriding the endpoint, we need to
        ;; provide the region in the override
        (assoc-in [:endpoints-override :region] region)
        ;; aws/client always expects an valid AWS region
        ;; even if we override the endpoint
        (assoc :region "us-east-1"))
    config))

(defn- config
  []
  (let [[config err] (envopts/opts env opts-spec)]
    (when err
      (throw (ex-info (envopts/errs-description err) err)))
    (-> config
        (fixup-override-config)
        (assoc :api :s3
               :credentials-provider (credentials/basic-credentials-provider (:creds config)))
        (dissoc :creds))))

(defn- remote-objects
  [session]
  (->> session
       (map (fn [[n id]]
              {:Key  (str (string/replace n #"/.*" "") "/" id)
               :Body (resource-content n session)}))))

(defn- put-session
  [client bucket session]
  (doseq [object (remote-objects session)]
    (aws/invoke client {:op      :PutObject
                        :request (assoc object
                                        :Bucket      bucket
                                        :ACL         "public-read"
                                        :ContentType "application/json")})))

(defn- delete-session
  [client bucket session]
  (doseq [object (remote-objects session)]
    (aws/invoke client {:op      :DeleteObject
                        :request (assoc object
                                        :Bucket bucket)})))

(defn remote-entities-fixture
  "A fixture that uploads the entities to the remote bucket.

  Entities are deleted (best effort) when `test-fn` returns.

  In the fixture's scope, `(entity-id NAME)` returns the UUID of the
  entity in the current session."
  [test-fn]
  (let [{:keys [bucket] :as c} (config)
        client (aws/client c)]
    (with-session
      (put-session client bucket *session*)
      (try
        (test-fn)
        (finally (delete-session client bucket *session*))))))

(comment
  (def client (aws/client (config)))

  (sort (keys (aws/ops client)))

  (aws/invoke client {:op :ListBuckets})

  (aws/invoke client {:op :PutObject :request {:Bucket "test-bucket", :Body "foo", :Key "foo.txt"}})
  )
