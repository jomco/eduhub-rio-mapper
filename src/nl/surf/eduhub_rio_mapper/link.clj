(ns nl.surf.eduhub-rio-mapper.link
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.rio :as rio]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]))

(defn- strip-duo [kw]
  (-> kw
      name
      (str/replace #"^duo:" "")))

(defn- duo-keyword [x]
  (keyword (str "duo:" (name x))))

(defn- rio-obj-name-in-set? [rio-obj name-set]
  (let [rio-obj-name (-> rio-obj first strip-duo keyword)]
    (some? (name-set rio-obj-name))))

(defn aangeboden-opleiding? [rio-obj]
  (rio-obj-name-in-set? rio-obj rio.loader/aangeboden-opleiding-namen))

(defn opleidingseenheid? [rio-obj]
  (rio-obj-name-in-set? rio-obj rio.loader/opleidingseenheid-namen))

(defn- xmlclj->duo-hiccup [x]
  {:pre [x (:tag x)]}
  (into
    [(duo-keyword (:tag x))]
    (mapv #(if (:tag %) (xmlclj->duo-hiccup %) %)
          (:content x))))

(defn- sleutel-finder [sleutel-name]
  (fn [element]
    (when (and (sequential? element)
               (= [:duo:kenmerken [:duo:kenmerknaam sleutel-name]]
                  (vec (take 2 element))))
      (-> element last last))))

(defn- sleutel-changer [id finder]
  (fn [element]
    (if (finder element)
      (assoc-in element [2 1] id)
      element)))

(defn- attribute-adapter [rio-obj k]
  (let [value
        (some #(and (sequential? %)
                    (or
                      (and (= (duo-keyword k) (first %))
                           (last %))
                      (and (= :duo:kenmerken (first %))
                           (= (name k) (get-in % [1 1]))
                           (get-in % [2 1]))))
              rio-obj)]
    (or value
        (when (and (= k :eigenAangebodenOpleidingSleutel)
                   (aangeboden-opleiding? rio-obj))
          "")
        (when (and (= k :eigenOpleidingseenheidSleutel)
                   (opleidingseenheid? rio-obj))
          "")
        (when (= k :toestemmingDeelnameSTAP)
          "GEEN_TOESTEMMING_VERLEEND"))))

(declare link-item-adapter)

(defn- child-adapter [rio-obj k]
  (->> rio-obj
       (filter #(and (sequential? %)
                     (= (duo-keyword k) (first %))
                     %))
       (map #(partial link-item-adapter %))))

;; Turns <prijs><soort>s</soort><bedrag>123</bedrag></prijs> into {:soort "s", bedrag 123}
(defn- nested-adapter [rio-obj k]
  (keep #(when (and (sequential? %)
                    (= (duo-keyword k) (first %)))
           (zipmap (map (comp keyword strip-duo first) (rest %))
                   (map last (rest %))))
        rio-obj))

;; These attributes have nested elements, e.g.:
;; <prijs>
;;   <bedrag>99.50</bedrag>
;;   <soort>collegegeld</soort>
;; </prijs
(def ^:private attributes-with-children #{:vastInstroommoment :prijs :flexibeleInstroom})

(defn- link-item-adapter [rio-obj k]
  (if (string? k)
    (child-adapter rio-obj k)         ; If k is a string, it refers to a nested type: Periode or Cohort.
    (if (attributes-with-children k)  ; These attributes are the only ones with child elements.
      (vec (nested-adapter rio-obj k))
      ; The common case is handling attributes.
      (attribute-adapter rio-obj k))))

(defn- linker [rio-obj]
  (rio/->xml (partial link-item-adapter rio-obj)
             (-> rio-obj first strip-duo)))

(defn make-linker [rio-config getter]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [id type] :keys [institution-oin] :as request}]
    {:pre [(:institution-oin request)]}
    (let [[action sleutelnaam]
          (case type
            "education-specification" ["aanleveren_opleidingseenheid" "eigenOpleidingseenheidSleutel"]
            ("course" "program")     ["aanleveren_aangebodenOpleiding" "eigenAangebodenOpleidingSleutel"])

          rio-obj  (rio.loader/rio-finder getter rio-config request)]
      (if (nil? rio-obj)
        (throw (ex-info "404 Not Found" {:phase :resolving}))
        (let [rio-obj (xmlclj->duo-hiccup rio-obj)
              ;; There is a mismatch between raadplegen and beheren for aangeboden-opleidingen.
              ;; raadplegen returns opleidingseenheidcode, but beheren requires opleidingseenheidSleutel.
              rio-obj (map #(if (and (sequential? %)
                                     (aangeboden-opleiding? rio-obj)
                                     (= :duo:opleidingseenheidcode (first %)))
                              (assoc % 0 :duo:opleidingseenheidSleutel)
                              %)
                           rio-obj)
              finder   (sleutel-finder sleutelnaam)
              old-id   (some finder rio-obj)
              new-id   id
              result   {(keyword sleutelnaam) (if (= old-id new-id)
                                                {:diff false}
                                                {:diff true :old-id old-id :new-id new-id})}
              rio-new (mapv (sleutel-changer new-id finder) (linker rio-obj))
              mutation {:action     action
                        :rio-sexp   [rio-new]
                        :sender-oin institution-oin}
              _success (mutator/mutate! mutation rio-config)]
          {:link result, :rio-sexp (:rio-sexp mutation)})))))
