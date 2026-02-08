(ns futon1a.ingest.open-world
  "Open-world ingest (stub)."
  (:require [futon1a.core.entity :as ent]))

(defn ingest!
  "Ingest open-world data.

   Inputs:
   - entities (vector)
   - relations (vector)

   Returns {:ok? true :counts {...}} or throws.
  "
  [{:keys [entities relations]}]
  (doseq [e entities]
    (ent/validate-entity e))
  (when (seq relations)
    (ent/validate-relations {:entities entities :relations relations}))
  {:ok? true
   :counts {:entities (count entities)
            :relations (count relations)}})
