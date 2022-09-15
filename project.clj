(defproject nl.surf/eduhub-rio-mapper "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[ch.qos.logback.contrib/logback-jackson "0.1.5"]
                 [ch.qos.logback.contrib/logback-json-classic "0.1.5"]
                 [ch.qos.logback/logback-classic "1.4.1"]
                 [clj-http "3.12.3"]
                 [com.fasterxml.jackson.core/jackson-core "2.13.4"]
                 ;; jackson-databind is excluded by clj-kondo, so needs to be specified directly
                 [com.fasterxml.jackson.core/jackson-databind "2.13.4"]
                 [com.taoensso/carmine "3.1.0"]
                 [com.velisco/strgen "0.2.4" :exclusions [org.clojure/tools.reader]]
                 [nl.jomco/envopts "0.0.4"]
                 [org.apache.santuario/xmlsec "3.0.1" :exclusions [org.slf4j/slf4j-api]]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.cli "1.0.206"]
                 [org.clojure/tools.logging "1.2.4"]]
  :java-source-paths ["src"]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[clj-commons/clj-yaml "0.7.110"]
                                  [clj-kondo "2022.09.08"]
                                  [expound "0.9.0"]
                                  [nl.jomco/proof-specs "0.1.4"]]
                   :plugins      [[lein-ancient "0.7.0"]]
                   :aliases      {"lint"           ["run" "-m" "clj-kondo.main" "--lint" "src"]
                                  ;; Enums are generated from yaml files in the open-education-api/specification github project.
                                  ;; To regenerate, call `lein generate-enums $path-to-open-education-api-specification`
                                  ;; This will regenerate `src/nl/surf/eduhub_rio_mapper/enums.clj`
                                  "generate-enums" ["run" "-m" "generate-enums.main"]
                                  ;; Simple prepopulated call to the raadplegen section of the rio test api. Spits out response body to STDOUT.
                                  "beheren-edn"    ["run" "-m" "xsd-to-edn.main"]
                                  "ls"             ["run" "-m" "nl.surf.eduhub-rio-mapper.cli.json-to-rio.main" "opvragen"]
                                  "cr"             ["run" "-m" "nl.surf.eduhub-rio-mapper.cli.json-to-rio.main" "beheren"]
                                  "rm"             ["run" "-m" "nl.surf.eduhub-rio-mapper.cli.json-to-rio.main" "verwijderen"]
                                  "updated"        ["run" "-m" "nl.surf.eduhub-rio-mapper.cli.simulate-perform-update.main"]
                                  "check-specs"    ["run" "-m" "nl.surf.eduhub-rio-mapper.check-specs"]
                                  "proof-specs"    ["run" "-m" "nl.jomco.proof-specs"
                                                    "--include-regexps" "nl.surf.*"
                                                    "--require-namespaces" "nl.surf.eduhub-rio-mapper.ooapi,nl.surf.eduhub-rio-mapper.rio"]}}}
  :test-selectors {:default (complement :redis)
                   :redis   :redis
                   :all     (constantly true)}
  :repl-options {:init-ns nl.surf.eduhub-rio-mapper.ooapi})
