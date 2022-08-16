(ns json-to-rio.main
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [clojure.tools.cli :as cli]
            [nl.surf.eduhub-rio-mapper.rio.opleidingseenheid :as opleidingseenheid]
            [nl.surf.eduhub-rio-mapper.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.soap :as soap]
            [nl.surf.eduhub-rio-mapper.xml-utils :as xml-utils]))

(def valid-opvragen-options [:eigenOpleidingseenheidSleutel :eigenAangebodenOpleidingSleutel
                             :onderwijsaanbiedercode :onderwijsbestuurcode :onderwijsbestuurCode :peildatum :pagina
                             :aangebodenOpleidingCode])

(def valid-aanleveren-options [:course :program :programCourse :privateProgram :educationspecification, :opleidingsrelatie])

(def valid-verwijderen-options [:aangebodenOpleidingCode :opleidingseenheidcode])

(def valid-opvragen-actions #{"rioIdentificatiecode" "aangebodenOpleiding" "aangebodenOpleidingenVanOrganisatie"
                              "opleidingseenhedenVanOrganisatie" "opleidingsrelatiesBijOpleidingseenheid"
                              "onderwijsaanbiedersVanOrganisatie"})

(def valid-mutatie-actions #{"aangebodenOpleiding" "opleidingseenheid" "opleidingsrelatie"})

(def cli-options
  [["-e" "--eigenOpleidingseenheidSleutel SLEUTEL" "eigenOpleidingseenheidSleutel"]
   ["-d" "--peildatum DATUM" "peildatum"]
   ["-p" "--pagina PAGINA" "pagina"]
   ["-C" "--course COURSE-FILE" "course"]
   ["-c" "--programCourse PROGRAM-FILE" "programCourse"]
   ["-P" "--program PROGRAM-FILE" "program"]
   ["-V" "--privateProgram PROGRAM-FILE" "privateProgram"]
   ["-r" "--opleidingsrelatie RELATIE-FILE" "opleidingsrelatie"]
   ["-E" "--educationspecification EDUCATIONSPECIFICATION-FILE" "educationspecification"]
   ["-o" "--onderwijsaanbiedercode CODE" "onderwijsaanbiedercode"]
   ["-b" "--onderwijsbestuurcode CODE" "onderwijsbestuurcode"]
   ["-B" "--onderwijsbestuurCode CODE" "onderwijsbestuurCode"]
   ["-O" "--opleidingseenheidcode CODE" "opleidingseenheidcode"]
   ["-a" "--aangebodenOpleidingCode CODE" "aangebodenOpleidingCode"]
   ["-h" "--help"]])

;;; Command line actions

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as all} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message summary :ok? true}

      errors
      {:exit-message errors :ok? false}

      (not= 1 (count arguments))
      {:exit-message "Only 1 argument allowed." :ok? false}

      :else
      (assoc all :target (first arguments)))))

;; This is a bit of an exception, since there is no associated API call in ooapi that returns this JSON format.
;; Included to make it easier to test the aanleveren_opleidingsrelatie call.
(defn ->opleidingsrelatie [{:keys [begindatum einddatum opleidingseenheidcode1 opleidingseenheidcode2]}]
  [:duo:opleidingsrelatie
   [:duo:begindatum begindatum]
   (when-let [d einddatum] [:duo:einddatum d])
   [:duo:opleidingseenheidcode opleidingseenheidcode1]
   [:duo:opleidingseenheidcode opleidingseenheidcode2]])

(def converters-by-type {:educationspecification opleidingseenheid/education-specification->opleidingseenheid
                         :course                 aangeboden-opl/course->aangeboden-opleiding
                         :program                #(aangeboden-opl/program->aangeboden-opleiding % "program")
                         :privateProgram         #(aangeboden-opl/program->aangeboden-opleiding % "privateProgram")
                         :programCourse          #(aangeboden-opl/program->aangeboden-opleiding % "course")
                         :opleidingsrelatie      ->opleidingsrelatie})

(defn ooapi->rio-sexp [ooapi-type json-string]
  (let [json (json/read-str json-string :key-fn keyword)
        converter (ooapi-type converters-by-type)
        output (converter json)]
    output))

(defn option->value
  "If key exists in options, look up key, process value (which could be the name of a json file) and add it to acc."
  [acc key options]
  (if-let [value (key options)]
    (conj acc (if (some #{key} valid-aanleveren-options)
                (ooapi->rio-sexp key (slurp value))         ; all valid-aanleveren-options have a json filename as value
                [(keyword (str "duo:" (name key))) value]))
    acc))

(def data-per-action-type {"opvragen" {:valid-options valid-opvragen-options :valid-actions valid-opvragen-actions :action-prefix "opvragen_"}
                           "verwijderen" {:valid-options valid-verwijderen-options :valid-actions valid-mutatie-actions :action-prefix "verwijderen_"}
                           "beheren" {:valid-options valid-aanleveren-options :valid-actions valid-mutatie-actions :action-prefix "aanleveren_"}})

(defn -main [command & args]
  (let [{:keys [exit-message options target]} (validate-args args)
        {:keys [valid-options valid-actions action-prefix]} (data-per-action-type command)]
    (or exit-message
        (if-not (valid-actions target)
          (str "Action " target " invalid.")
          (let [action (str action-prefix target)
                rio-datamap (if (= "opvragen" command) soap/raadplegen soap/beheren)
                rio-sexp (reduce (fn [v k] (option->value v k options)) [] valid-options)
                credentials (xml-utils/credentials "keystore.jks" "xxxxxx" "test-surf" "truststore.jks" "xxxxxx")
                xml (soap/prepare-soap-call action rio-sexp rio-datamap credentials)]
            (spit "last.xml" xml)
            (let [response (xml-utils/format-xml (xml-utils/post-body (:dev-url rio-datamap) xml rio-datamap action credentials))]
              (println response)))))))
