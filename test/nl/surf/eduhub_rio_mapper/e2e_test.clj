(ns nl.surf.eduhub-rio-mapper.e2e-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.e2e-helper :refer :all]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :refer [remote-entities-fixture]])
  (:import (java.util UUID)))

(use-fixtures :once with-running-mapper remote-entities-fixture)

(def test-eigensleutel (str (UUID/randomUUID)))
(def parent-rio-code-atom (atom nil))
(def child-rio-code-atom (atom nil))

(deftest ^:e2e create-edspecs
  (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect RIO to be empty, when you start fresh."

    (let [job (post-job :dry-run/upsert :education-specifications
                        (ooapi "education-specifications/parent"))]
      (is (= "done" (job-result-status job)))
      (is (= "not-found" (:status (job-result-attributes job))))))

  #_ ;; TODO this fails because eduspec is added to rio but linking currently silently fails
  (testing "scenario [4b]: Test /job/upsert with the program. You can expect an error, because the edspec child is not upserted."

    (let [job (post-job :upsert :education-specifications
                        (ooapi "education-specifications/child"))]
      (is (= "error" (job-result-status job)))))

  (testing "scenario [1b]: Test /job/upsert with the edspec parent. You can expect 'done' and a opleidingeenheid in RIO is inserted."

    (let [job (post-job :upsert :education-specifications
                        (ooapi "education-specifications/parent"))]
      (is (= "done" (job-result-status job)))
      (is (job-result-opleidingseenheidcode job)
          "result contains opleidingseenheidcode")

      ;; keep opleidingseenheidcode for linking later
      (reset! parent-rio-code-atom (job-result-opleidingseenheidcode job)))

    (testing "(you can repeat this to test an update of the same data.)"

      (let [job (post-job :upsert :education-specifications
                          (ooapi "education-specifications/parent"))]
        (is (= "done" (job-result-status job))))))

  (testing "scenario [1a]: Test /job/dry-run to see the difference between the edspec parent in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same."

    (let [job   (post-job :dry-run/upsert :education-specifications
                          (ooapi "education-specifications/parent"))
          attrs (job-result-attributes job)]
      (is (= "done" (job-result-status job)))
      (is (= "found" (:status (job-result-attributes job))))
      (is (not (has-diffs? attrs)))))

  (testing "scenario [1c]: Test /job/upsert with the edspec child. You can expect 'done' and  a variant in RIO is inserted met een relatie met de parent."

    (let [job      (post-job :upsert :education-specifications
                             (ooapi "education-specifications/child"))
          rio-code (job-result-opleidingseenheidcode job)]
      (is (= "done" (job-result-status job)))
      ;; TODO: this tests fails some times, probably a timing issue, may we can try for a while?
      (is (rio-has-relation? @parent-rio-code-atom rio-code))

      ;; keep opleidingeenheidcode for linking later
      (reset! child-rio-code-atom rio-code)))

  (testing "scenario [2a]: Test /job/link of the edspec parent and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed."

    (let [job   (post-job :link @parent-rio-code-atom
                          :education-specifications test-eigensleutel)
          attrs (job-result-attributes job)]
      (is (= "done" (job-result-status job)))
      (is (has-diffs? attrs)))

    (testing "(you can repeat this to expect an error becoause the new 'eigen sleutel' already exists.)"

      (let [job (post-job :link @child-rio-code-atom :education-specifications
                          test-eigensleutel)]
        (is (= "error" (job-result-status job))))))

  (testing "scenario [2d]: Test /job/unlink to reset the edspec parent to an empty 'eigen sleutel'."

    (let [job (post-job :unlink @parent-rio-code-atom :education-specifications)]
      (is (= "done" (job-result-status job)))))

  (testing "scenario [2b]: Test /job/link to reset the edspec parent to the old 'eigen sleutel'."

    (let [job   (post-job :link @parent-rio-code-atom :education-specifications
                          (ooapi "education-specifications/parent"))
          attrs (job-result-attributes job)]
      (is (= "done" (job-result-status job)))
      (is (has-diffs? attrs)))))

(deftest ^:e2e try-to-create-edspecs-with-invalid-data
  ;; scenario [3a]: Test /job/upsert/<invalid type> to see how the rio mapper reacts on an invalid api call. You can expect a 404 response.
  ;; scenario [3b]: Test /job/upsert with an edspec parent with an invalid type attribute. You can expect 'error'.
  :TODO)

(deftest ^:e2e create-a-program-for-the-edspec-child
  ;; scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh.
  ;; scenario [4c]: Test /job/delete with the program. You can expect an error, because the program is not upserted yet.
  ;; scenario [4b]: Test /job/upsert with the program. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)
  ;; scenario [4a]: Test /job/dry-run to see the difference between the program in OOAPI en de opleidingeenheid in RIO. You can expect them to be the same.
  ;; scenario [5a]: Test /job/link of the program and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed. (you can repeat this to expect an error becoause the new 'eigen sleutel' already exists.)
  ;; scenario [5d]: Test /job/unlink to reset the program to an empty 'eigen sleutel'.
  ;; scenario [5b]: Test /job/link to reset the program to the old 'eigen sleutel'.
  :TODO)

(deftest ^:e2e try-to-create-a-program-with-invalid-data
  ;; scenario [6a]: Test /job/upsert with a program with an invalid onderwijsaanbieder attribute. You can expect 'error'.
  ;; scenario [6b]: Test /job/upsert with a program with an invalid onderwijslocatie attribute. You can expect 'error'.
  :TODO)

(deftest ^:e2e create-a-course-with-its-own-edspec
  ;; scenario [7a]: Test /job/upsert with the edspec for a course. You can expect 'done'.
  ;; scenario [7c]: Test /job/dry-run to see the difference between the course in OOAPI en de aangeboden opleiding in RIO. You can expect RIO to be empty, when you start fresh.
  ;; scenario [7e]: Test /job/delete with the course. You can expect an error, because the course is not upserted yet.
  ;; scenario [7d]: Test /job/upsert with the course. You can expect a new aangeboden opleiding. This aangeboden opleiding includes a periode and a cohort. (you can repeat this to test an update of the same data.)
  ;; scenario [7c]: Test /job/dry-run to see the difference between the course in OOAPI en de aangeboden opleiding in RIO. You can expect them to be the same.
  ;; scenario [8a]: Test /job/link of the course and create a new 'eigen sleutel'. You can expect the 'eigen sleutel' to be changed. (you can repeat this to expect an error becoause the new 'eigen sleutel' already exists.)
  ;; scenario [8d]: Test /job/unlink to reset the course to an empty 'eigen sleutel'.
  ;; scenario [8b]: Test /job/link to reset the course to the old 'eigen sleutel'.
  :TODO)

;; TODO add test to upsert non existing eduspec
