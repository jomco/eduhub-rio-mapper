(ns nl.surf.eduhub-rio-mapper.worker
  (:require [clojure.core.async :as async]
            [nl.surf.eduhub-rio-mapper.redis :as redis]
            [taoensso.carmine :as car])
  (:import java.util.UUID))

(defn- prefix-key
  [{:keys [redis-key-prefix]
    :or {redis-key-prefix "eduhub-rio-mapper.worker"}}
   key]
  (str redis-key-prefix ":" key))

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
    (when-not
      (car/wcar redis-conn
                (car/parse-bool
                 (car/lua "if redis.call('get', _:k) == _:token then
                             redis.call('del', _:k);
                             return 1;
                           else
                             return 0;
                           end"
                          {:k k} {:token token})))
      (throw (ex-info "Lock lost before release!" {:lock-name k})))))

(defn extend-lock!
  "Extend TTL on lock `k` with `token` by `ttl-ms`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config}
   k token ttl-ms]
  (let [k (lock-name config k)]
    (when-not
      (car/wcar redis-conn
                (car/parse-bool
                 (car/lua "if redis.call('get', _:k) == _:token then
                             redis.call('set', _:k, _:token, 'px', _:ttl-ms);
                             return 1;
                           else
                             return 0;
                           end"
                          {:k k} {:token token, :ttl-ms ttl-ms})))
      (throw (ex-info "Lock lost before extend!" {:lock-name k})))))

(defn- queue-key [config institution-schac-home]
  (prefix-key config (str "queue:" institution-schac-home)))

(defn- busy-queue-key [config institution-schac-home]
  (prefix-key config (str "busy-queue:" institution-schac-home)))

(defn- set-status!
  "Set status of job.
  Should only be called by worker, not by job itself!"
  [config job status & payload]
  (comment "TODO" config job status payload))

(defn- add-to-queue!
  [{:keys [redis-conn] :as config}
   {:keys [institution-schac-home] :as job}
   side]
  ((case side
     :left redis/lpush
     :right redis/rpush)
   redis-conn (queue-key config institution-schac-home) job)
  (set-status! config job :pending)
  job)

(defn queue!
  "Queue a job."
  [config job]
  (add-to-queue! config job :right))

(defn queue-first!
  "Queue a job in front of the queue."
  [config job]
  (add-to-queue! config job :left))

(defn- pop-job!
  "Pop job from an institution queue.
  The job is stored on the institution busy queue to allow restarting
  when aborted.  Returns a job or `nil` when queue is empty."
  [{:keys [redis-conn] :as config}
   institution-schac-home]
  (redis/lmove redis-conn
               (queue-key config institution-schac-home)
               (busy-queue-key config institution-schac-home)
               "LEFT"
               "RIGHT"))

(defn- occupied-queues
  "Return list of institution ids having jobs queued."
  [{:keys [redis-conn institution-schac-homes] :as config}]
  (let [script (apply str "local ids = {};"
                      (conj
                       (mapv #(str "if redis.call('llen', _:i" % ") > 0 then
                                      table.insert(ids, _:v" % ")
                                    end;")
                             (range (count institution-schac-homes)))
                       "return ids;"))
        ks   (into {} (map #(vector (keyword (str "i" %1))
                                    (queue-key config %2))
                           (range (count institution-schac-homes))
                           institution-schac-homes))
        vs   (into {} (map #(vector (keyword (str "v" %1)) %2)
                           (range (count institution-schac-homes))
                           institution-schac-homes))]
    (car/wcar redis-conn (car/lua script ks vs))))

(defn- recover-aborted-job!
  "Recover aborted job, when available, and queue it."
  [{:keys [redis-conn] :as config}
   institution-schac-home]
  (redis/lmove redis-conn
               (busy-queue-key config institution-schac-home)
               (queue-key config institution-schac-home)
               "RIGHT"
               "LEFT"))

(defn- job-done!
  "Remove job from busy queue."
  [{:keys [redis-conn] :as config}
   institution-schac-home]
  (redis/lpop redis-conn (busy-queue-key config institution-schac-home)))

(defn- worker-loop
  "Worker loop.

  It loops through list of institutions and tries to acquire a lock
  for them to do work (this ensures jobs are run in order for an
  institution).  When the lock has been acquired it pops a job from
  the queue and runs it.  If the job failed but can be restarted (see
  also `nack?`), we waits `retry-wait-ms` and puts the job back in the
  front of the queue.

  Configuration value `run-job!` is a function which takes one
  argument, the job payload, it returns the result of the job.  The
  `nack?` functions take one argument, the result of the job, and
  returns true when job failed but can be retried.  The `error?`
  function takes one argument, the result of the job, and returns true
  when the job failed."
  [{:keys  [institution-schac-homes
            lock-ttl-ms
            max-retries
            nap-ms
            retry-wait-ms
            run-job!
            nack?
            error?]
    :or    {lock-ttl-ms   10000
            max-retries   3
            nap-ms        1000
            retry-wait-ms 5000}
    :as    config}
   stop-atom]
  (assert (and (seq institution-schac-homes)
               (fn? run-job!) (fn? nack?) (fn? error?)))

  (let [timeout-ms (/ lock-ttl-ms 2)]
    (loop [ids (occupied-queues config)]
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
                          (run-job! job))]
                  (set-status! config job :in-progress)

                  ;; wait for job to finish and refresh lock regularly
                  ;; while waiting
                  (loop []
                    (let [result (async/alt!! c ([v] v)
                                              (async/timeout timeout-ms) ::ping)]
                      (extend-lock! config id @token lock-ttl-ms)

                      (if (= ::ping result)
                        (recur)
                        (if (nack? result)
                          (if (and (::retries job) (>= (::retries job) max-retries))
                            (set-status! config job :time-out)
                            (do
                              (queue-first! config (update job ::retries (fnil inc 0)))
                              ;; extend lock lease to retry-wait
                              (extend-lock! config id @token retry-wait-ms)
                              ;; prevent release
                              (reset! token nil)))

                          ;; ack
                          (if (error? result)
                            (set-status! config job :error result)
                            (set-status! config job :done result)))))))

                ;; done, remove from busy
                (job-done! config id))

              (finally
                (some->> @token (release-lock! config id)))))

          (when-not @stop-atom
            ;; next institution
            (recur ids)))
        (when-not @stop-atom
          ;; do nothing for a while to give redis a rest
          (async/<!! (async/timeout nap-ms))

          ;; start all over
          (recur (occupied-queues config)))))))

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

(defn wait-worker
  "Wait for worker to finish."
  [[_ chan]]
  (async/<!! chan))

(defn stop-worker!
  "Signal worker to stop and wait for it to finish."
  [[stop-atom :as worker]]
  (reset! stop-atom true)
  (wait-worker worker))

(defn purge!
  "Delete all queues and locks."
  [{:keys [redis-conn institution-schac-homes]
    :as config}]
  (doseq [institution-schac-home institution-schac-homes]
    (redis/del redis-conn (queue-key config institution-schac-home))
    (redis/del redis-conn (busy-queue-key config institution-schac-home))
    (redis/del redis-conn (lock-name config institution-schac-home))))
