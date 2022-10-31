(ns nl.surf.eduhub-rio-mapper.mapper-integration-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [nl.surf.eduhub-rio-mapper.errors :refer [errors?]]
    [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.test-helper :refer [load-json]]
    [nl.surf.eduhub-rio-mapper.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(def institution-oin "123O321")
(def rio-opleidingsid "1234O1234")
(def ooapi-id "f2d020bc-5fac-b2e9-4ea7-4b35a08dfbeb")
(def config {:rio-config {:credentials (xml-utils/credentials "test/keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")}})

(defn mock-ooapi-loader [{:keys [eduspec program-course offerings]}]
  (fn [{:keys [::ooapi/type]}]
    (case type
      "education-specification" (load-json eduspec)
      ("course" "program") (load-json program-course)
      (load-json offerings))))

;; resolver takes sender-oin and ooapi-id and returns code
;; ooapi-loader takes request with type and id and returns request
;; mutator takes {:keys [action sender-oin rio-sexp]} returns json
(defn- mock-handle-updated [ooapi-loader]
  (as-> updated-handler/update-mutation f
        (partial updated-handler/resolve-id f (fn rio-resolver [_sender-oin _id] rio-opleidingsid))
        (partial ooapi.loader/load-entities f ooapi-loader)))

;; resolver takes sender-oin and ooapi-id and returns code
;; mutator takes {:keys [action sender-oin rio-sexp]} returns json
(defn- mock-handle-deleted [id type institution-oin]
  (let [handle-deleted (as-> updated-handler/deletion-mutation f
                             (partial updated-handler/resolve-id f (fn rio-resolver [_sender-oin _id] rio-opleidingsid)))]
    (handle-deleted {::ooapi/id       id
                     ::ooapi/type     type
                     :institution-oin institution-oin})))

(defn- simulate-upsert [ooapi-loader xml-response ooapi-type]
  {:pre [(some? xml-response)]}
  (let [handle-updated (mock-handle-updated ooapi-loader)
        result (handle-updated {::ooapi/id   ooapi-id
                                ::ooapi/type ooapi-type
                                :institution-oin institution-oin})]
    (if (errors? result)
      result
      (let [mutator (mutator/make-mutator (:rio-config config) (constantly {:status 200 :success true :body xml-response}))]
        (mutator result)))))

(defn- simulate-delete [ooapi-type xml-response]
  {:pre [(some? xml-response)]}
  (let [result (mock-handle-deleted ooapi-id ooapi-type institution-oin)]
    (if (errors? result)
      result
      (let [mutator (mutator/make-mutator (:rio-config config) (constantly {:status 200 :success true :body xml-response}))]
        (mutator result)))))

(deftest test-handle-updated-eduspec-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course nil
                                         :offerings      nil})
        handle-updated (mock-handle-updated ooapi-loader)
        actual (handle-updated {::ooapi/id   ooapi-id
                                ::ooapi/type "education-specification"
                                :institution-oin institution-oin})]
    (is (nil? (:errors actual)))
    (is (= "EN TRANSLATION: Computer Science" (-> actual :ooapi :name first :value)))))

(deftest test-make-eduspec-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course nil
                                         :offerings      nil})
        actual (simulate-upsert ooapi-loader
                                (slurp (io/resource "fixtures/rio/integration-eduspec-0.xml"))
                                "education-specification")]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :aanleveren_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-make-program-0
  (let [ooapi-loader (mock-ooapi-loader {:eduspec        "fixtures/ooapi/integration-eduspec-0.json"
                                         :program-course "fixtures/ooapi/integration-program-0.json"
                                         :offerings      "fixtures/ooapi/integration-program-offerings-0.json"})
        actual (simulate-upsert ooapi-loader
                                (slurp (io/resource "fixtures/rio/integratie-program-0.xml"))
                                "program")]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :aanleveren_aangebodenOpleiding_response :requestGoedgekeurd)))))

(deftest test-remove-eduspec-0
  (let [actual (simulate-delete "education-specification"
                                (slurp (io/resource "fixtures/rio/integration-deletion-eduspec-0.xml")))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :verwijderen_opleidingseenheid_response :requestGoedgekeurd)))))

(deftest test-remove-program-0
  (let [actual (simulate-delete "program"
                                (slurp (io/resource "fixtures/rio/integratie-deletion-program-0.xml")))]
    (is (nil? (:errors actual)))
    (is (= "true" (-> actual :verwijderen_aangebodenOpleiding_response :requestGoedgekeurd)))))
