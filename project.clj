(defproject nl.surf/eduhub-rio-mapper "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[com.velisco/strgen "0.2.4" :exclusions [org.clojure/tools.reader]]
                 [org.apache.santuario/xmlsec "3.0.0"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.slf4j/slf4j-api "1.7.36"]
                 [org.slf4j/slf4j-nop "1.7.36"]]
  :java-source-paths ["src"]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[clj-commons/clj-yaml "0.7.108"]
                                  [clj-kondo "2022.06.22"]
                                  [nl.jomco/proof-specs "0.1.4"]]
                   :plugins      [[lein-ancient "0.7.0"]]
                   :aliases      {"lint"           ["run" "-m" "clj-kondo.main" "--lint" "src"]
                                  ;; Enums are generated from yaml files in the open-education-api/specification github project.
                                  ;; To regenerate, call `lein generate-enums $path-to-open-education-api-specification`
                                  ;; This will regenerate `src/nl/surf/eduhub_rio_mapper/enums.clj`
                                  "generate-enums" ["run" "-m" "generate-enums.main"]
                                  "proof-specs"    ["run" "-m" "nl.jomco.proof-specs"
                                                    "--include-regexps" "nl.surf.*"
                                                    "--require-namespaces" "nl.surf.eduhub-rio-mapper.ooapi,nl.surf.eduhub-rio-mapper.rio"]}}}
  :repl-options {:init-ns nl.surf.eduhub-rio-mapper.ooapi})
