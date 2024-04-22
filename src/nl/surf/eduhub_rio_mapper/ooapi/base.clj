(ns nl.surf.eduhub-rio-mapper.ooapi.base)

(defn education-specification-id
  "Return the education specification id for the given ooapi entity.

  Takes an EducationSpecification or a Course or a Program"
  [entity]
  (or (:educationSpecification entity)
      (:educationSpecificationId entity)))
