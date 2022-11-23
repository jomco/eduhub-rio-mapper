;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.errors
  "Handling errors.

  When running a bunch of computations in sequence, we want to be able
  to be able to bail out and return useful information on problems
  encountered.

  A computation can return errors (which is a map with an :errors
  key), or any other non-problematic result."
  {:deprecated true})

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
  "Like `when-let` but when `test` has errors, returns `test`.

  Bindings can consist of multiple `form` `test` pairs, as in
  `let`. When no binding has errors, evaluates `body` with bindings.

      (when-result [res1 (try-first ..)
                    res2 (try-second bar res1)]
         (do-something res2 res1))

  Will return `res1` if it has errors.  Otherwise, will return `res2`
  if it has errors.  Otherwise, evaluates `(do-something ...)`.

  See also `errors?`"
  ;; indent bindings as "special", see
  ;; https://docs.cider.mx/cider/indent_spec.html
  {:style/indent 1}
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
