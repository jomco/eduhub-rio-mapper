(ns nl.surf.eduhub-rio-mapper.worker-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(def num-of-workers 50)

(def config
  {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
   :redis-key-prefix "eduhub-rio-mapper-test"
   :worker           {:nap-ms        10
                      :retry-wait-ms 10
                      :queues        ["foo" "bar"]
                      :queue-fn      :queue
                      :retryable-fn  (constantly false)
                      :error-fn      (constantly false)
                      :set-status-fn (fn [_ _ & [_]] (comment "nop"))}})

(defn wait-for-expected [expected val-atom max-sec]
  (loop [ttl (* max-sec 10)]
    (when (and (pos? ttl) (not= expected @val-atom))
      (Thread/sleep 100)
      (recur (dec ttl))))
  (is (= expected @val-atom)))

(deftest ^:redis worker
  (let [job-runs (atom {"foo" [], "bar" []})
        config   (-> config
                     (assoc-in [:worker :run-job-fn]
                               (fn [{:keys [queue job-nr]}]
                                 (swap! job-runs update queue conj job-nr))))]
    (worker/purge! config)

    ;; queue some without running workers
    (doseq [n (range 100)]
      (worker/enqueue! config {:queue "foo", :job-nr n}))
    (doseq [n (range 50)]
      (worker/enqueue! config {:queue "bar", :job-nr n}))

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]

      ;; wait till work is done and check it
      (let [expected {"foo" (range 100)
                      "bar" (range 50)}]
        (wait-for-expected expected job-runs 30))

      ;; queue more work
      (doseq [n (range 100 200)]
        (worker/enqueue! config {:queue "foo", :job-nr n}))
      (doseq [n (range 50 100)]
        (worker/enqueue! config {:queue "bar", :job-nr n}))

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
                          (assoc-in [:worker :max-retries] max-retries)
                          (assoc-in [:worker :run-job-fn]
                                    (fn [job]
                                      (reset! last-seen-job job)
                                      :from-job))
                          (assoc-in [:worker :retryable-fn]
                                    (fn [result]
                                      (= result :from-job))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]
      ;; queue a nacking job
      (worker/enqueue! config {:queue "foo"})

      ;; wait job to finish and check it
      (let [expected {:queue           "foo"
                      ::worker/retries max-retries}]
        (wait-for-expected expected last-seen-job 5))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis two-retries
  (let [last-seen-job (atom nil)
        max-retries   3
        config        (-> config
                          (assoc-in [:worker :max-retries] max-retries)
                          (assoc-in [:worker :run-job-fn]
                                    (fn [job]
                                      (reset! last-seen-job job)
                                      (::worker/retries job)))
                          (assoc-in [:worker :retryable-fn]
                                    (fn [result]
                                      (not= 2 result))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]
      ;; queue a nacking job
      (worker/enqueue! config {:queue "foo"})

      ;; wait job to finish and check it
      (let [expected {:queue           "foo"
                      ::worker/retries 2}]
        (wait-for-expected expected last-seen-job 5))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis retry-wait
  (let [last-seen-job (atom nil)
        retry-wait-ms 3000
        config        (-> config
                          (assoc-in [:worker :retry-wait-ms] retry-wait-ms)
                          (assoc-in [:worker :run-job-fn]
                                    (fn [job]
                                      (reset! last-seen-job job)
                                      (::worker/retries job)))
                          (assoc-in [:worker :retryable-fn]
                                    (fn [result]
                                      (not= 1 result))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]

      ;; queue a job to be retried once
      (worker/enqueue! config {:queue "foo"})

      (let [before-ms (System/currentTimeMillis)
            expected  {:queue           "foo"
                       ::worker/retries 1}]
        (wait-for-expected expected last-seen-job 10)
        (is (>= (- (System/currentTimeMillis) before-ms)
                retry-wait-ms)
            "wall time should be at least retry-wait-ms"))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis set-status
  (let [last-seen-status (atom nil)
        config           (-> config
                             (assoc-in [:worker :error-fn] :error?)
                             (assoc-in [:worker :run-job-fn] identity)
                             (assoc-in [:worker :set-status-fn]
                                       (fn [job status & [data]]
                                         (reset! last-seen-status {:job    job
                                                                   :status status
                                                                   :data   data}))))]
    (worker/purge! config)

    ;; queue a successful job
    (worker/enqueue! config {:queue "foo"})
    (is (= {:job {:queue "foo"}, :status :pending, :data nil}
           @last-seen-status))

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]

      ;; wait for successful job to finish
      (wait-for-expected {:job    {:queue "foo"}
                          :status :done
                          :data   {:queue "foo"}}
                         last-seen-status 10)

      ;; queue and wait for error job
      (worker/enqueue! config {:queue "foo", :error? true})
      (wait-for-expected {:job    {:queue "foo", :error? true}
                          :status :error
                          :data   {:queue  "foo", :error? true}}
                         last-seen-status 10)

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))
