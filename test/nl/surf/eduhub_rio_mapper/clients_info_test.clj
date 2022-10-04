(ns nl.surf.eduhub-rio-mapper.clients-info-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]))

(deftest test-make-clients-info
  (let [info (clients-info/read-clients-data {:path "test/test-clients.json"})]
    (is (= {:client-id              "rio-mapper-dev.jomco.nl"
            :institution-schac-home "demo06.test.surfeduhub.nl"
            :institution-oin        "0000000700025BE00000"}
           (clients-info/client-info info "rio-mapper-dev.jomco.nl")))))
