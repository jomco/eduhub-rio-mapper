(ns nl.surf.rio-mapper.e2e.fixtures.session
  "Helper bindings for converting fixture entity names to uuids.
  These are intented to be used in tests.

  Use `(use-fixtures :once with-name-session)` at the top of your
  tests, then, in your tests, call `(name->id NAME)` to get a uuid for
  the entity with that name."
  (:require [nl.surf.rio-mapper.e2e.fixtures.entities :as entities]
            [nl.surf.rio-mapper.e2e.fixtures.ids :as ids]))

(def ^:dynamic
  *config*
  nil)

(defmacro with-name-session
  [& body]
  `(binding [*config* {:session (ids/mk-session)
                       :nameset (:nameset (entities/fixtures))}]
     ~@body))

(defn name->uuid
  [n]
  (when-not *config*
    (throw (IllegalStateException. "Can't call `session/name->uuid` outside `with-name-session`.")))
  (ids/name->uuid (:nameset *config*) (:session *config*) n))

(defn uuid->name
  [u]
  (when-not *config*
    (throw (IllegalStateException. "Can't call `session/uuid->name` outside `with-name-session`.")))
  (ids/uuid->name (:nameset *config*) u))
