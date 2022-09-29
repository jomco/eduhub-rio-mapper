(ns nl.surf.eduhub-rio-mapper.errors
  "Handling errors.

  When running a bunch of computations in sequence, we want to be able
  to be able to bail out and return useful information on problems
  encountered.

  A computation can return errors (which is a map with an :errors
  key), or any other non-problematic result.")

(defn errors?
  "Return true if `x` has errors."
  [x]
  (and (map? x)
       (contains? x :errors)))

(defn result?
  "Return true if `x` has no errors."
  [x]
  (not (errors? x)))

(defn guard-errors
  "Returns self if x has no errors, otherwise throws exception with given msg."
  [x msg]
  (if (errors? x)
    (throw (ex-info msg x))
    x))

(defmacro result->
  "Thread and return result or errors.

  Threads forms as with `->`, short-circuiting and returning any
  intermediate result that has errors."
  [expr & forms]
  (if (seq forms)
    (let [e (gensym)]
      `(let [~e ~expr]
         (if (errors? ~e)
           ~e
           ~(if (list? (first forms)) ;; form can be atom or list
              `(result-> (~(ffirst forms) ~e ~@(next (first forms)))
                         ~@(next forms))
              `(result-> (~(first forms) ~e)
                         ~@(next forms))))))
    ;; no forms left, return expr as is
    expr))

(defmacro when-result
  "Like `when-let` but when `test` has errors, returns `test`.

  Bindings can consist of multiple `form` `test` pairs, as in
  `let`. When no binding has errors, evaluates `body` with bindings.

      (when-result [res1 (try-first ..)
                    res2 (try-second bar res1)]
         (do-something res2 res1))

  Will return `res1` if it has errors.  Otherwise, will return `res2`
  if it has errors.  Otherwise, evaluates `(do-something ...)`.

  See also `errors?`"
  [[form test & rest-bindings] & body]
  ;; here to make for nicer
  ;; documentation
  `(let [temp# ~test]
     (if (errors? temp#)
       temp#
       (let [~form temp#]
         ~(if (seq rest-bindings)
            `(when-result ~(vec rest-bindings)
               (do ~@body))
            `(do ~@body))))))
