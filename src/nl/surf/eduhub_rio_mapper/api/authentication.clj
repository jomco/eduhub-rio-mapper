(ns nl.surf.eduhub-rio-mapper.api.authentication
  "Authenticate incoming HTTP API requests using SURF Conext.

  This uses the OAuth2 Client Credentials flow for authentication."
  (:require [clj-http.client :as client]
            [clojure.core.memoize :as memo]
            [nl.surf.eduhub-rio-mapper.http :as http]
            [nl.surf.eduhub-rio-mapper.logging :refer [with-mdc]]
            [ring.util.response :as response]))

(defn bearer-token
  [{{:strs [authorization]} :headers}]
  (some->> authorization
           (re-matches #"Bearer ([^\s]+)")
           second))

(defn make-token-authenticator
  "Make a token authenticator that uses the OIDC `introspection-endpoint`.

  Returns a authenticator that tests the token using the given
  `instrospection-endpoint` and returns the token's client id if the
  token is valid."
  [{:keys [introspection-endpoint client-id client-secret]}]
  {:pre [introspection-endpoint client-id client-secret]}
  (fn token-authenticator
    [token]
    ;; TODO: Pass trace id?
    (let [{:keys [status] :as response}
          (client/post (str introspection-endpoint) ;; may be URI object
                       {:form-params {:token token}
                        :accept      :json
                        :coerce      :always
                        :as          :json
                        :basic-auth  [client-id client-secret]})]
      (when (and (= http/ok status)
                 (get-in response [:body :active]))
        (get-in response [:body :client_id])))))

(defn cache-token-authenticator
  "Cache results of the authenticator for `ttl-minutes` minutes."
  [authenticator {:keys [ttl-minutes]}]
  (assert (< 0 ttl-minutes))
  (memo/ttl authenticator :ttl/threshold (* 1000 60 ttl-minutes)))

(defn wrap-authentication
  "Authenticate calls to ring handler `f` using `token-authenticator`.

  The token authenticator will be called with the Bearer token from
  the incoming http request. If the authenticator returns a client-id,
  the client-id gets added to the request as `:client-id` and the
  request is handled by `f`. If the authenticator returns `nil`, the
  request is forbidden.

  If no bearer token is provided, an `http/unauthorized` response is
  returned."
  [f token-authenticator]
  (fn [request]
    (if-let [token (bearer-token request)]
      (if-let [client-id (token-authenticator token)]
        ;; set client-id on request and response (for tracing)
        (with-mdc {:client-id client-id}
          (-> request
              (assoc :client-id client-id)
              f
              (assoc :client-id client-id)))
        (response/status http/forbidden))
      (response/status http/unauthorized))))