(ns generate-enums.main
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as string]
            [clojure.java.io :as io]))

(defn generate-enum
  "Generates a single enum definition as a string."
  [dir-path file-name yaml-path enum-name docstring]
  (let [values (-> (io/file dir-path file-name)
                   (.getPath)
                   (slurp)
                   (yaml/parse-string)
                   (get-in yaml-path))]
    (str "(def "
         enum-name
         "\n  \""
         docstring
         "\"\n  #{"
         (string/join " " (map #(str "\"" % "\"") values))
         "})\n\n")))

(defn -main
  "This turns the enumerables in the yaml files on open-education-api/specification into clojure sets"
  [specification-dir]
  (let [enum-path (io/file specification-dir "v5-rc" "enumerations")
        schema-path (io/file specification-dir "v5-rc" "schemas")
        enum-clojure-file (str
                            ";; DO NOT EDIT - CHANGES WILL BE OVERWRITTEN\n"
                            ";; This file is generated automatically via lein generate-enums $PATH\n\n"
                            "(ns nl.surf.eduhub-rio-mapper.enums)\n\n"
                            (generate-enum enum-path "codeType.yaml" [:x-ooapi-extensible-enum] "codeTypes"
                                           "Enum gebruikt in EducationSpecification voor primaryCode en otherCodes.")
                            (generate-enum enum-path "educationSpecificationType.yaml" [:enum] "educationSpecificationTypes"
                                           "Enum gebruikt in EducationSpecification voor educationSpecificationType.")
                            (generate-enum enum-path "formalDocument.yaml" [:enum] "formalDocumentTypes"
                                           "Enum gebruikt in EducationSpecification voor formalDocument.")
                            (generate-enum enum-path "level.yaml" [:enum] "levels"
                                           "Enum gebruikt in EducationSpecification voor level.")
                            (generate-enum enum-path "sector.yaml" [:enum] "sectors"
                                           "Enum gebruikt in EducationSpecification voor sector.")
                            (generate-enum schema-path "StudyLoadDescriptor.yaml" [:properties :studyLoadUnit :enum] "studyLoadUnits"
                                           "Enum gebruikt in EducationSpecification voor studyLoad."))]
    (spit "src/nl/surf/eduhub_rio_mapper/enums.clj" enum-clojure-file)))

