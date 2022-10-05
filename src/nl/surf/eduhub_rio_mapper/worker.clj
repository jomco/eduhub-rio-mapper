(ns nl.surf.eduhub-rio-mapper.worker
  (:require [clojure.core.async :as async]
            [nl.surf.eduhub-rio-mapper.redis :as redis]
            [taoensso.carmine :as car])
  (:import java.util.UUID))

(defn- prefix-key
  [{:keys [redis-key-prefix]
    :or {redis-key-prefix "eduhub-rio-mapper"}}
   key]
  (str redis-key-prefix ".worker:" key))

(defn- lock-name [config queue]
  (prefix-key config (str "lock:" queue)))

(defn acquire-lock!
  "Acquire lock on `queue` with `ttl-ms`.
  Return a unique token which can be used to release / extend the
  lock or `nil` when lock is already taken."
  [{:keys [redis-conn] :as config} queue ttl-ms]
  (let [k     (lock-name config queue)
        token (str (UUID/randomUUID))]
    (when (= "OK" (redis/set redis-conn k token "NX" "PX" ttl-ms))
      token)))

(defn release-lock!
  "Release lock on `queue`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config} queue token]
  (let [k (lock-name config queue)]
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
  "Extend TTL on lock on `queue` with `token` by `ttl-ms`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config} queue token ttl-ms]
  (let [k (lock-name config queue)]
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

(defn- queue-key [config queue]
  (prefix-key config (str "queue:" queue)))

(defn- busy-queue-key [config queue]
  (prefix-key config (str "busy-queue:" queue)))

(defn- add-to-queue!
  [{:keys [redis-conn]
    {:keys [queue-fn
            set-status-fn]} :worker
    :as config} job side]
  ((case side
     :left redis/lpush
     :right redis/rpush)
   redis-conn (queue-key config (queue-fn job)) job)
  (set-status-fn job :pending)
  job)

(defn enqueue!
  "Enqueue a job."
  [config job]
  (add-to-queue! config job :right))

(defn enqueue-first!
  "Enqueue a job in front of the queue."
  [config job]
  (add-to-queue! config job :left))

(defn- pop-job!
  "Pop job from a `queue`.
  The job is stored on the associated busy queue to allow restarting
  when aborted.  Returns a job or `nil` when queue is empty."
  [{:keys [redis-conn] :as config} queue]
  (redis/lmove redis-conn
               (queue-key config queue)
               (busy-queue-key config queue)
               "LEFT"
               "RIGHT"))

(defn- occupied-queues
  "Return list of queues having jobs queued."
  [{:keys [redis-conn] {:keys [queues]} :worker :as config}]
  (let [script (apply str "local ids = {};"
                      (conj
                       (mapv #(str "if redis.call('llen', _:i" % ") > 0 then
                                      table.insert(ids, _:v" % ")
                                    end;")
                             (range (count queues)))
                       "return ids;"))
        ks   (into {} (map #(vector (keyword (str "i" %1))
                                    (queue-key config %2))
                           (range (count queues))
                           queues))
        vs   (into {} (map #(vector (keyword (str "v" %1)) %2)
                           (range (count queues))
                           queues))]
    (car/wcar redis-conn (car/lua script ks vs))))

(defn- recover-aborted-job!
  "Recover aborted job, when available, and queue on `queue` it."
  [{:keys [redis-conn] :as config} queue]
  (redis/lmove redis-conn
               (busy-queue-key config queue)
               (queue-key config queue)
               "RIGHT"
               "LEFT"))

(defn- job-done!
  "Remove job from `queue` from its busy queue."
  [{:keys [redis-conn] :as config} queue]
  (redis/lpop redis-conn (busy-queue-key config queue)))

(defn- worker-loop
  "Worker loop.

  It loops through list of queues and tries to acquire a lock for them
  to do work (this ensures jobs are run in order for a queue).  When
  the lock has been acquired it pops a job from the queue and runs it.
  If the job failed but can be restarted (see also `retryable?`), we
  waits `retry-wait-ms` and puts the job back in the front of the
  queue.

  Configuration takes several functions:

  - `queue-fn` a function which takes one argument, a job, and returns
    a queue name for that job.

  - `run-job-fn` a function which takes one argument, a job, it
    returns the result of the job.

  - `set-status-fn` a function which takes two or three arguments; a
    job, a status symbol and some extra data.

  - `error-fn` a function which takes one argument, the result of the
    job, and returns true when the job failed.

  - `retryable-fn` a functions which one argument, the result of the
    job, and returns true when job failed but can be retried.
  "
  [{{:keys [queues
            lock-ttl-ms
            max-retries
            nap-ms
            retry-wait-ms

            ;; functions
            queue-fn
            error-fn
            retryable-fn
            run-job-fn
            set-status-fn]
     :or {lock-ttl-ms   10000
          max-retries   3
          nap-ms        1000
          retry-wait-ms 5000}} :worker
    :as                        config}
   stop-atom]
  {:pre [(seq queues)
         (fn? run-job-fn) (fn? set-status-fn)
         (ifn? retryable-fn) (ifn? error-fn) (ifn? queue-fn)]}

  (let [timeout-ms (/ lock-ttl-ms 2)]
    (loop [queues (occupied-queues config)]
      (if-let [[queue & queues] queues]
        (do
          ;; try and acquire lock of queue
          (when-let [token (some-> (acquire-lock! config queue lock-ttl-ms) atom)]
            (try
              ;; recover jobs which have been aborted
              (recover-aborted-job! config queue)

              (when-let [job (pop-job! config queue)]
                ;; run job asynchronous
                (let [c (async/thread
                          (.setName (Thread/currentThread) (str "runner-" queue))
                          (run-job-fn job))]
                  (set-status-fn job :in-progress)

                  ;; wait for job to finish and refresh lock regularly
                  ;; while waiting
                  (loop []
                    (let [result (async/alt!! c ([v] v)
                                              (async/timeout timeout-ms) ::ping)]
                      (extend-lock! config queue @token lock-ttl-ms)

                      (if (= ::ping result)
                        (recur)

                        ;; nack
                        (if (retryable-fn result)
                          (if (and (::retries job) (>= (::retries job) max-retries))
                            (set-status-fn job :time-out)
                            (do
                              (enqueue-first! config (update job ::retries (fnil inc 0)))
                              ;; extend lock lease to retry-wait
                              (extend-lock! config queue @token retry-wait-ms)
                              ;; prevent release
                              (reset! token nil)))

                          ;; ack
                          (if (error-fn result)
                            (set-status-fn job :error result)
                            (set-status-fn job :done result)))))))

                ;; done, remove from busy
                (job-done! config queue))

              (finally
                (some->> @token (release-lock! config queue)))))

          (when-not @stop-atom
            ;; next queue
            (recur queues)))
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
  [{:keys [redis-conn] {:keys [queues]} :worker :as config}]
  (doseq [queue queues]
    (redis/del redis-conn (queue-key config queue))
    (redis/del redis-conn (busy-queue-key config queue))
    (redis/del redis-conn (lock-name config queue))))
