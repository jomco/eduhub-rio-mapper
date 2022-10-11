(ns nl.surf.eduhub-rio-mapper.logging
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.http :as http])
  (:import java.util.UUID
           org.slf4j.MDC))

(def redact-key-re
  "Regex matching keys for entries that should be redacted from logs."
  #"(?i:)pass|secret|client.id|proxy.options|auth") ;; proxy options can contain authentication info

(def redact-placeholder
  "String that will replace a redacted entry.

  Also see [[redact-key-re]] and [[with-mdc]]."
  "XXX-REDACTED")

(defn ->mdc-key
  "Convert x to key to use in the Mapped Diagnostic Context.

  MDC keys must be strings. For qualified keywords includes the
  namespace in the MDC key; \"my.foo/bar\", otherwise
  uses [[clojure.core/str]] to coerce to string."
  [x]
  (if (keyword? x)
    (subs (str x) 1) ;; convert with ns if fully qualified key, but
    ;; leave out colon: :foo/bar => "foo/bar"

    (str x)))

(defn ->mdc-val
  "Convert x to value to use in the Mapped Diagnostic Context.

  MDC values must be strings -- the MDC is a flat string/string
  map. Assumes x is a keyword, a sequential collection of 'simple'
  values (converted to a comma-separated string) or has a reasonable
  [[clojure.core/str]] coercion."
  [x]
  (cond
    (keyword? x)
    (subs (str x) 1)

    (sequential? x)
    (string/join ", " (map #(str "'" (->mdc-val %) "'") x))

    :else
    (str x)))

(defn ->mdc-entry
  "Convert a `[key value]` pair to a Mapped Diagnostic Context entry.

  Uses [[->mdc-key]] and [[->mdc-val]] to convert. In entries with
  key matching [[redact-key-re]], the value will be replaced with
  [[redact-placeholder]]"
  [[k v]]
  (let [k (->mdc-key k)]
    [k (if (re-find redact-key-re k)
         redact-placeholder
         (->mdc-val v))]))

(defmacro with-mdc
  "Adds the map `m` to the Mapped Diagnostic Context (MDC) and executes `body`.

  Enties in `m` are processed using [[->mdc-entry]].

  Removes the added keys from the MDC after `body` is executed.

  See also [[redact-key-re]] and https://logback.qos.ch/manual/mdc.html"
  [m & body]
  `(let [m# ~m]
     (try
       (doseq [[k# v#] (map ->mdc-entry m#)]
         (MDC/put k# v#))
       ~@body
       (finally
         (doseq [k# (keys m#)]
           (MDC/remove (->mdc-key k#)))))))

(defn wrap-request-logging
  [f]
  (fn [{:keys                        [request-method uri]
        {:keys [trace-id parent-id]} :traceparent
        :as                          request}]
    (let [method (string/upper-case (name request-method))]
      (with-mdc {:request_method method
                 :url            uri
                 :trace-id       trace-id
                 :parent-id      parent-id}
        (when-let [{:keys [status client-id institution-schac-home institution-oin] :as response} (f request)]
          (with-mdc {:http_status            status
                     :client-id              client-id
                     :institution-schac-home institution-schac-home
                     :institution-oin        institution-oin}
            (log/info status method uri)
            response))))))

(defn log-exception
  [e id]
  ;; Request info is provided in MDC, see wrap-request-logging
  (with-mdc {:error_id id}
    (log/error e (str "Error " id))))

(defn wrap-exception-logging
  [f]
  (fn [request]
    (try
      (f request)
      (catch Throwable e
        (let [id (str (UUID/randomUUID))]
          (log-exception e id)
          {:status http/internal-server-error
           :body   {:error    "Internal Server Error"
                    :error-id id}})))))

(defn wrap-logging
  [f]
  (-> f
      wrap-exception-logging
      wrap-request-logging))
