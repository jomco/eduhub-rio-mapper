(defproject nl.surf/eduhub-rio-mapper "0.1.0-SNAPSHOT"
  :description "Mapping between Surf's OOAPI and DUO's RIO."
  :url "https://github.com/jomco/eduhub-rio-mapper"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[ch.qos.logback.contrib/logback-jackson "0.1.5"]
                 [ch.qos.logback.contrib/logback-json-classic "0.1.5"]
                 [ch.qos.logback/logback-classic "1.4.11"]

                 [clj-http "3.12.3"]
                 [clj-time "0.15.2"]
                 [commons-io "2.15.0"]

                 [cheshire "5.12.0"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.15.3"
                  ;; CVE-2020-28491 (ring-json -> cheshire)
                  :upgrade :keep-this-version]
                 [com.fasterxml.jackson.core/jackson-core "2.16.0"]

                 [com.taoensso/carmine "3.3.2"
                  ;; because [clj-http "3.12.3"] uses [commons-codec "1.15"]
                  :exclusions [commons-codec]]
                 [com.velisco/strgen "0.2.5"
                  ;; unused in this project
                  :exclusions [criterium
                               org.clojure/clojurescript]]

                 [compojure "1.7.0"]
                 [ring/ring-jetty-adapter "1.10.0"
                   ;; included below
                  :exclusions [org.eclipse.jetty/jetty-server]]
                 [org.eclipse.jetty/jetty-server "9.4.53.v20231009"
                   ;; CVE-2023-40167, CVE-2023-44487
                  :upgrade :keep-this-version]

                 [nl.jomco/envopts "0.0.4"]
                 [nl.jomco/ring-trace-context "0.0.8"]
                 [nl.jomco/clj-http-status-codes "0.1"]

                 [org.apache.santuario/xmlsec "4.0.0"]

                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/core.memoize "1.0.257"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/tools.cli "1.0.219"]
                 [org.clojure/tools.logging "1.2.4"]
                 [org.clojure/tools.reader "1.3.7"]

                 [ring/ring-core "1.10.0"]
                 [ring/ring-defaults "0.3.4" :upgrade :keep-this-version]
                 [ring/ring-json "0.5.1"]]

  :java-source-paths ["src"]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[clj-commons/clj-yaml "1.0.26"]
                                  [clj-kondo "2023.05.26"]
                                  [expound "0.9.0"]
                                  [nl.jomco/proof-specs "0.1.7"]
                                  [ring/ring-mock "0.4.0"]]
                   :plugins      [[lein-ancient "0.7.0"]]
                   :aliases      {"lint"           ["run" "-m" "clj-kondo.main" "--lint" "src" "test"]
                                  "check-deps"     ["ancient" "check" ":no-profiles" ":exclude" "keep-this-version"]
                                  "upgrade-deps"   ["ancient" "upgrade" ":no-profiles" ":exclude" "keep-this-version"]
                                  ;; Enums are generated from yaml files in the open-education-api/specification github project.
                                  ;; To regenerate, call `lein generate-enums $path-to-open-education-api-specification`
                                  ;; This will regenerate `src/nl/surf/eduhub_rio_mapper/enums.clj`
                                  "generate-enums" ["run" "-m" "generate-enums.main"]
                                  ;; Simple prepopulated call to the raadplegen section of the rio test api. Spits out response body to STDOUT.
                                  "beheren-edn"    ["run" "-m" "xsd-to-edn.main"]
                                  "mapper"         ["run" "-m" "nl.surf.eduhub-rio-mapper.cli"]
                                  "proof-specs"    ["run" "-m" "nl.jomco.proof-specs"
                                                    "--verbose"
                                                    "--num-vals" "10"
                                                    "--limit-ms" "5000"
                                                    "--include" "nl.surf.*"
                                                    "--require" "nl.surf.eduhub-rio-mapper.ooapi"
                                                    "--require" "nl.surf.eduhub-rio-mapper.rio"]}}

             ;; Make tests fail on conflicting deps. This isn't in the
             ;; root of the project.clj, because that will abort any
             ;; mismatch on additional tooling loaded in development -
             ;; i.e. cider-nrepl and refactor-nrepl generally have
             ;; conflicting transitive dependencies.
             :test {:pedantic?    :abort
                    :dependencies [[pjstadig/humane-test-output "0.11.0"]]
                    :injections   [(require 'pjstadig.humane-test-output)
                                   (pjstadig.humane-test-output/activate!)]}


             :uberjar {:aot :all}}

  :uberjar-name "eduhub-rio-mapper.jar"
  :main nl.surf.eduhub-rio-mapper.cli

  :test-selectors {:default (complement :redis)
                   :redis   :redis
                   :all     (constantly true)}

  :repl-options {:init-ns nl.surf.eduhub-rio-mapper.ooapi})
