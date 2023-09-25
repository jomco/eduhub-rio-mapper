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

(ns nl.surf.eduhub-rio-mapper.ooapi.offerings
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.ooapi.common :as common]
            [nl.surf.eduhub-rio-mapper.ooapi.Offering :as-alias Offering]))

(s/def ::Offering/consumer (s/keys))
(s/def ::Offering/priceInformationItem (s/keys))

(s/def ::Offering/offeringId ::common/uuid)
(s/def ::Offering/endDate ::common/date)
(s/def ::Offering/startDate ::common/date)
(s/def ::Offering/modeOfDelivery ::common/modeOfDelivery)
(s/def ::Offering/enrollStartDate ::common/date)
(s/def ::Offering/enrollEndDate ::common/date)
(s/def ::Offering/maxNumberStudents number?)
(s/def ::Offering/priceInformation (s/coll-of ::Offering/priceInformationItem))
(s/def ::Offering/consumers (s/coll-of ::Offering/consumer))
(s/def ::Offering/flexibleEntryPeriodStart ::common/date)
(s/def ::Offering/flexibleEntryPeriodEnd ::common/date)

(defn has-mode-of-delivery? [x]
  (or (:modeOfDelivery x)
      (some #(and (:modeOfDelivery %)
                  (= "rio" (:consumerKey %)))
            (:consumers x))))

(defn has-registration-status? [x]
  (some #(and (#{"open" "closed"} (:registrationStatus %))
              (= "rio" (:consumerKey %)))
        (:consumers x)))

(s/def ::Offering
  (s/and
    has-mode-of-delivery?
    has-registration-status?
    (s/keys :req-un [::Offering/offeringId
                     ::Offering/endDate
                     ::Offering/startDate
                     ::Offering/enrollStartDate]
            :opt-un [::Offering/enrollEndDate
                     ::Offering/maxNumberStudents
                     ::Offering/modeOfDelivery
                     ::Offering/priceInformation
                     ::Offering/consumers
                     ::Offering/flexibleEntryPeriodStart
                     ::Offering/flexibleEntryPeriodEnd])))

(s/def ::Offering/items (s/coll-of ::Offering))

(s/def ::OfferingsRequest
  (s/keys :req-un [::Offering/items]))
