(ns nl.surf.eduhub-rio-mapper.remote-entities-helper
  "Always fresh OOAPI entities in SWIFT ObjectStore for running tests on.

  Using the `remote-entities-fixture` fixture function, all JSON files
  in the `fixtures/remote-entities` directory are uploaded to the
  configured SWIFT ObjectStore.  Before uploading, the base names of
  the JSON files are replaced by random UUIDs and the paths and base
  names replace with those UUIDs when referenced using `{{` and `}}`.

  For instance `education-specifications/parent.json` may contain:

  ```
    {
      \"level\": \"bachelor\",
      \"parent\": \"{{education-specifications/parent}}\",
      \"organization\": \"{{organizations/acme}}\",
  ```

  Say we generated a UUID of `beefbeef-beef-beef-beef-beefbeefbeef`
  for this entity and `cafecafe-cafe-cafe-cafe-cafecafecafe` for
  `organizations/acme`, the uploaded version will be named
  `education-specifications/beefbeef-beef-beef-beef-beefbeefbeef.json`
  and contain:

  ```
    {
      \"level\": \"bachelor\",
      \"parent\": \"beefbeef-beef-beef-beef-beefbeefbeef\",
      \"organization\": \"cafecafe-cafe-cafe-cafe-cafecafecafe\",
  ```

  and the uploaded version of `organizations/acme` will be named
  `organizations/cafecafe-cafe-cafe-cafe-cafecafecafe.json`.

  This only works if the following environment variables are set:

  - OS_USERNAME
  - OS_PASSWORD
  - OS_PROJECT_NAME
  - OS_AUTH_URL
  - OS_CONTAINER_NAME

  Note: the public endpoint of the SWIFT ObjectStore container should
  exposed by the gateway and accessable to the test client."
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts])
  (:import java.util.UUID))

(def opts-spec
  {:os-username            ["ObjectStore username" :str
                            :in [:username]]
   :os-password            ["ObjectStore password" :str
                            :in [:password]]
   :os-user-domain-name    ["ObjectStore user domain name" :str
                            :in [:user-domain-name]
                            :default "Default"]
   :os-project-domain-name ["ObjectStore project domain name" :str
                            :in [:user-domain-name]
                            :default "Default"]
   :os-auth-url            ["ObjectStore auth url" :str
                            :in [:auth-url]]
   :os-container-name      ["ObjectStore container name" :str
                            :in [:container-name]]
   :os-project-name        ["ObjectStore project name" :str
                            :in [:project-name]]})

(defn- config
  []
  (let [[config err] (envopts/opts env opts-spec)]
    (when err
      (throw (ex-info (envopts/errs-description err) err)))
    config))



;;; ObjecStore / Swift client

(defn swift-auth-info
  [{:keys [username password user-domain-name project-domain-name project-name auth-url]
    :or   {user-domain-name "Default" project-domain-name "Default"}}]
  {:pre [username password user-domain-name project-domain-name project-name auth-url]}
  (let [res (client/post (str auth-url "/auth/tokens")
                         {:accept       :json
                          :as           :json
                          :content-type :json
                          :form-params  {:auth {:identity {:methods  ["password"]
                                                           :password {:user {:domain   {:name user-domain-name}
                                                                             :name     username
                                                                             :password password}}}
                                                :scope    {:project {:domain {:name project-domain-name}
                                                                     :name   project-name}}}}})]
    {:token       (get-in res [:headers "x-subject-token"])
     :storage-url (->> (get-in res [:body :token :catalog 1 :endpoints])
                       (some (fn [{:keys [interface url]}]
                               (when (= "public" interface)
                                 url))))}))

(defn os-req
  "Create a request to the ObjectStore."
  [{:keys [token storage-url] :as _info} method path]
  {:headers {"X-Auth-Token" token}
   :url     (str (string/replace storage-url #"/$" "") path)
   :accept  :json
   :as      :json
   :method  method})

(defn os-delete-container
  [info container-name]
  (client/request (os-req info :delete (str "/" container-name))))

(defn os-create-public-container
  [info container-name]
  (client/request (-> (os-req info :put (str "/" container-name))
                      (assoc-in [:headers "X-Container-Read"] ".r:*,.rlistings"))))

(defn os-list-containers
  [info]
  (->> (client/request (os-req info :get "/"))
       :body
       (map :name)))

(defn os-list-objects
  [info container]
  (->> (client/request (os-req info :get (str "/" container)))
       :body
       (map :name)))

(defn os-put-object
  [info container-name {:keys [path body]}]
  {:pre [(seq path) body]}
  (client/request (assoc (os-req info :put (str "/" container-name "/" path))
                         :content-type :json
                         :body body)))

(defn os-delete-object
  [info container-name {:keys [path]}]
  {:pre [(seq path)]}
  (client/request (os-req info :delete (str "/" container-name "/" path))))



;;;; Entities on disk to that will be mirrord on the object store
;;;; using a unique set of ids per session.

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
  "Map of name => uuid for remote entities. Will be set by `with-session`."
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


(defn- remote-objects
  [session]
  (->> session
       (map (fn [[n id]]
              {:path (str (string/replace n #"/.*" "") "/" id)
               :body (resource-content n session)}))))

(defn- put-session
  [info container-name session]
  (println "Adding remote entities to" container-name)
  (doseq [object (remote-objects session)]
    (os-put-object info container-name object)))

(defn- delete-session
  [info container-name session]
  (println "Removing entities from" container-name)
  (doseq [object (remote-objects session)]
    (os-delete-object info container-name object)))

(defn remote-entities-fixture
  "A fixture that uploads the entities to the remote container.

  Container will be created if necessary. Entities are deleted when
  the JVM terminates.

  In the fixture's scope, `(entity-id NAME)` returns the UUID of the
  entity in the current session."
  [test-fn]
  (let [{:keys [container-name] :as cfg} (config)
        info                             (swift-auth-info cfg)]
    (when-not (some #(= container-name %) (os-list-containers info))
      (println "Creating container '" container-name "'")
      (os-create-public-container info container-name))
    (with-session
      (let [session        *session*
            delete-session #(delete-session info container-name session)]
        (.addShutdownHook (Runtime/getRuntime) (Thread. delete-session))
        (put-session info container-name *session*)
        (test-fn)))))

(comment
  ;; delete public container
  (let [{:keys [container-name]
         :as   config} (config)
        info           (swift-auth-info config)]
    (doseq [path (os-list-objects info container-name)]
      (os-delete-object info container-name {:path path}))))
