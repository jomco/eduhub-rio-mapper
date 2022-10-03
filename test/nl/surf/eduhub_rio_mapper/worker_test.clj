(ns nl.surf.eduhub-rio-mapper.worker-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(def config
  {:redis-conn              {:pool {} :spec {:uri "redis://localhost"}}
   :redis-key-prefix        "eduhub-rio-mapper.worker-test"
   :institution-schac-homes ["foo" "bar"]
   :nap-ms                  10
   :retry-wait-ms           10
   :nack?                   (constantly false)
   :error?                  (constantly false)})

(defn wait-for-expected [expected val-atom max-sec]
  (loop [ttl (* max-sec 10)]
    (when (and (pos? ttl) (not= expected @val-atom))
      (Thread/sleep 100)
      (recur (dec ttl))))
  (is (= expected @val-atom)))

(deftest ^:redis worker
  (let [job-runs (atom {"foo" [], "bar" []})
        config   (-> config
                     (assoc :run-job!
                            (fn [{:keys [institution-schac-home job-nr]}]
                              (swap! job-runs update institution-schac-home conj job-nr))))]
    (worker/purge! config)

    ;; queue some without running workers
    (doseq [n (range 100)]
      (worker/queue! config {:institution-schac-home "foo", :job-nr n}))
    (doseq [n (range 50)]
      (worker/queue! config {:institution-schac-home "bar", :job-nr n}))

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range 100))]

      ;; wait till work is done and check it
      (let [expected {"foo" (range 100)
                      "bar" (range 50)}]
        (wait-for-expected expected job-runs 30))

      ;; queue more work
      (doseq [n (range 100 200)]
        (worker/queue! config {:institution-schac-home "foo", :job-nr n}))
      (doseq [n (range 50 100)]
        (worker/queue! config {:institution-schac-home "bar", :job-nr n}))

      ;; wait till work is done and check it
      (let [expected {"foo" (range 200)
                      "bar" (range 100)}]
        (wait-for-expected expected job-runs 30)
        (is (= expected @job-runs)))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis all-retries
  (let [last-seen-job (atom nil)
        max-retries   3
        config        (-> config
                          (assoc :max-retries max-retries)
                          (assoc :run-job!
                                 (fn [job]
                                   (reset! last-seen-job job)
                                   :from-job))
                          (assoc :nack?
                                 (fn [result]
                                   (= result :from-job))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range 100))]
      ;; queue a nacking job
      (worker/queue! config {:institution-schac-home "foo"})

      ;; wait job to finish and check it
      (let [expected {:institution-schac-home "foo"
                      ::worker/retries        max-retries}]
        (wait-for-expected expected last-seen-job 5))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis two-retries
  (let [last-seen-job (atom nil)
        max-retries   3
        config        (-> config
                          (assoc :max-retries max-retries)
                          (assoc :run-job!
                                 (fn [job]
                                   (reset! last-seen-job job)
                                   (::worker/retries job)))
                          (assoc :nack?
                                 (fn [result]
                                   (not= 2 result))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range 100))]
      ;; queue a nacking job
      (worker/queue! config {:institution-schac-home "foo"})

      ;; wait job to finish and check it
      (let [expected {:institution-schac-home "foo"
                      ::worker/retries        2}]
        (wait-for-expected expected last-seen-job 5))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis retry-wait
  (let [last-seen-job (atom nil)
        retry-wait-ms 3000
        config        (-> config
                          (assoc :retry-wait-ms retry-wait-ms)
                          (assoc :run-job!
                                 (fn [job]
                                   (reset! last-seen-job job)
                                   (::worker/retries job)))
                          (assoc :nack?
                                 (fn [result]
                                   (not= 1 result))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range 100))]

      ;; queue a job to be retried once
      (worker/queue! config {:institution-schac-home "foo"})

      (let [before-ms (System/currentTimeMillis)
            expected  {:institution-schac-home "foo"
                       ::worker/retries        1}]
        (wait-for-expected expected last-seen-job 10)
        (is (>= (- (System/currentTimeMillis) before-ms)
                retry-wait-ms)
            "wall time should be at least retry-wait-ms"))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))
