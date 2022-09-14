(ns nl.surf.eduhub-rio-mapper.errors-test
  (:require [nl.surf.eduhub-rio-mapper.errors :refer [when-result]]
            [clojure.test :refer [deftest is]]))

(deftest test-when-result
  (is (= {:errors "yes"}
         (when-result [foo {:errors "yes"}]
           :skipped)))

  (is (= {:result :ok}
         (when-result [foo :ok]
           {:result foo})))

  (is (= {:result :ok}
         (when-result [foo :ok
                       result {:result foo}]
           result)))

    (is (= {:errors "yes"}
         (when-result [foo :ok
                       result {:errors "yes"}]
           :skipped))))
