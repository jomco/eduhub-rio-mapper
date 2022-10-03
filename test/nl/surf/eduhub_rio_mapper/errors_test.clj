(ns nl.surf.eduhub-rio-mapper.errors-test
  (:require [clojure.test :refer [deftest is]]
            [nl.surf.eduhub-rio-mapper.errors :refer [when-result]]))

(deftest test-when-result
  (is (= {:errors "yes"}
         (when-result [_ {:errors "yes"}]
           :skipped)))

  (is (= {:result :ok}
         (when-result [foo :ok]
           {:result foo})))

  (is (= {:result :ok}
         (when-result [foo :ok
                       result {:result foo}]
           result)))

  (is (= {:errors "yes"}
         (when-result [_ :ok
                       _ {:errors "yes"}]
           :skipped))))
