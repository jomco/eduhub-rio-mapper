(ns nl.surf.eduhub-rio-mapper.jobs
  (:require [clojure.core.async :as async]
            [nl.surf.eduhub-rio-mapper.redis :as redis])
  (:import java.util.UUID))

(defn- prefix-key
  [{:keys [prefix-key-prefix]
    :or {prefix-key-prefix "eduhub-rio-mapper.jobs"}}
   key]
  (str prefix-key-prefix ":" key))

(defn- lock-name [config k]
  (prefix-key config (str "lock:" k)))

(defn acquire-lock!
  "Acquire lock `k` with `ttl-ms`.
  Return a unique token which can be used to release / extend the
  lock or `nil` when lock is already taken."
  [{:keys [redis-conn] :as config}
   k ttl-ms]
  (let [k     (lock-name config k)
        token (str (UUID/randomUUID))]
    (when (= "OK" (redis/set redis-conn k token "NX" "PX" ttl-ms))
      token)))

(defn release-lock!
  "Release lock `k`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config}
   k token]
  (let [k (lock-name config k)]
    (if (= token (redis/get redis-conn k))
      (redis/del redis-conn k)
      (throw (ex-info "Lock lost before release!" {:lock-name k})))))

(defn extend-lock!
  "Extend TTL on lock `k` with `token` by `ttl-ms`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config}
   k token ttl-ms]
  (let [k (lock-name config k)]
    (if (= token (redis/get redis-conn k))
      (redis/set redis-conn k token "PX" ttl-ms)
      (throw (ex-info "Lock lost before extend!" {:lock-name k})))))

(defn- queue-key [config institution-id]
  (prefix-key config (str "queue:" institution-id)))

(defn- busy-queue-key [config institution-id]
  (prefix-key config (str "busy-queue:" institution-id)))

(defn- set-status!
  [config job status & payload]
  (comment "TODO" config job status payload))

(defn queue!
  "Queue a job."
  [{:keys [redis-conn] :as config}
   {:keys [institution-id] :as job}]
  (redis/rpush redis-conn (queue-key config institution-id) job)
  (set-status! config job :pending)
  job)

(defn queue-first!
  "Queue a job in front of the queue."
  [{:keys [redis-conn] :as config}
   {:keys [institution-id] :as job}]
  (redis/lpush redis-conn (queue-key config institution-id) job)
  (set-status! config job :pending)
  job)

(defn- pop-job!
  "Pop job from an institution queue.
  The job is stored on the institution busy queue to allow restarting
  when aborted.  Returns a job or `nil` when queue is empty."
  [{:keys [redis-conn] :as config}
   institution-id]
  (redis/lmove redis-conn
               (queue-key config institution-id)
               (busy-queue-key config institution-id)
               "LEFT"
               "RIGHT"))

(defn- recover-aborted-job!
  "Recover aborted job, when available, and queue it."
  [{:keys [redis-conn] :as config}
   institution-id]
  (redis/lmove redis-conn
               (busy-queue-key config institution-id)
               (queue-key config institution-id)
               "RIGHT"
               "LEFT"))

(defn- job-done!
  "Remove job from busy queue."
  [{:keys [redis-conn] :as config}
   institution-id]
  (redis/lpop redis-conn (busy-queue-key config institution-id)))

(defn- run-job!
  "Execute job."
  [config job]
  (comment "TODO" config job))

(defn- nack?
  "Returns `true` when a job failed but is retryable."
  [job result]
  (comment "TODO" job result))

(defn- error?
  "Returns `true` when job resulted in an error."
  [job result]
  (comment "TODO" job result))

(defn- worker-loop
  "Worker loop.

  It loops through list of institutions and tries to acquire a lock
  for them to do work (this ensures jobs are run in order for an
  institution).  When the lock has been acquired it pops a job from
  the queue and runs it.  If the job failed but can be restarted (see
  also `nack?`), we waits `retry-wait-ms` and puts the job back in the
  front of the queue."
  [{:keys [institution-ids
           lock-ttl-ms
           max-retries
           nack?
           nap-ms
           retry-wait-ms
           run-job!]
    :or   {lock-ttl-ms   10000
           max-retries   3
           nack?         nack?
           nap-ms        1000
           retry-wait-ms 5000
           run-job!      run-job!}
    :as   config}
   stop-atom]
  (assert (seq institution-ids))
  (assert (< retry-wait-ms (* lock-ttl-ms 75)))

  (let [timeout-ms (/ lock-ttl-ms 2)]
    ;; constantly loop through list of institutions
    (loop [ids institution-ids]
      (if-let [[id & ids] ids]
        (do
          ;; try and acquire lock of institution
          (when-let [token (some-> (acquire-lock! config id lock-ttl-ms) atom)]
            (try
              ;; recover jobs which have been aborted
              (recover-aborted-job! config id)

              (when-let [job (pop-job! config id)]
                ;; run job asynchronous
                (let [c (async/thread
                          (.setName (Thread/currentThread) (str "runner-" id))
                          (run-job! config job))]
                  (set-status! config job :in-progress)

                  ;; wait for job to finish and refresh lock regularly
                  ;; while waiting
                  (loop []
                    (let [r (async/alt!! c ([v] v)
                                         (async/timeout timeout-ms) ::timeout)]
                      (extend-lock! config id @token lock-ttl-ms)

                      (if (= ::timeout r) ;; timeout
                        (recur)
                        (if (nack? job r)
                          (if (and (::retries job) (>= (::retries job) max-retries))
                            (set-status! config job :time-out)
                            (do
                              (queue-first! config (update job ::retries (fnil inc 0)))
                              ;; extend lock lease to retry-wait
                              (extend-lock! config id @token retry-wait-ms)
                              ;; prevent release
                              (reset! token nil)))

                          ;; ack
                          (if (error? job r)
                            (set-status! config job :error r)
                            (set-status! config job :done r)))))))

                ;; done, remove from busy
                (job-done! config id))

              (finally
                (some->> @token (release-lock! config id)))))

          (when-not @stop-atom
            ;; next institution
            (recur ids)))
        (when-not @stop-atom
          (async/<!! (async/timeout nap-ms))

          ;; start all over
          (recur institution-ids))))))

(defn start-worker!
  "Start a worker loop asynchronously.
  Returns an atom and go block channel which can be used by
  `stop-worker!` to stop the created worker."
  [config]
  (let [stop-atom (atom nil)]
    [stop-atom
     (async/thread
       (.setName (Thread/currentThread) "worker")
       (worker-loop config stop-atom)
       :stopped)]))

(defn stop-worker!
  "Signal worker to stop and wait for it to finish."
  [[stop-atom chan]]
  (reset! stop-atom true)
  (async/<!! chan))

(defn purge!
  "Delete all queues and locks."
  [{:keys [redis-conn institution-ids]
    :as config}]
  (doseq [institution-id institution-ids]
    (redis/del redis-conn (queue-key config institution-id))
    (redis/del redis-conn (busy-queue-key config institution-id))
    (redis/del redis-conn (lock-name config institution-id))))
