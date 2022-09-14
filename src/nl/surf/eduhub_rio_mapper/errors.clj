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
  "Like `when-let` but when test has errors, returns test.

  When not errors evaluate body with bindings."
  [[form tst & rst-bindings] & body]
  `(let [temp# ~tst]
     (if (errors? temp#)
       temp#
       (let [~form temp#]
         ~(if (seq rst-bindings)
             `(when-result ~(vec rst-bindings)
                (do ~@body))
             `(do ~@body))))))
