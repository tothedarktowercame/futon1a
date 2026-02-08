(ns futon1a.ingest.open-world
  "Open-world ingest."
  (:require [futon1a.core.entity :as ent]
            [futon1a.model.registry :as registry]
            [futon1a.model.validation :as mv]))

(defn- validate-model
  [{:keys [entity require-model?]}]
  (let [model-id (:entity/type entity)
        descriptor (registry/get-model model-id)]
    (cond
      descriptor
      (mv/validate-model! {:model entity
                           :required-keys (:required descriptor)})

      require-model?
      (throw (ex-info "model descriptor missing"
                      {:error (mv/layer4-error :missing-model
                                               {:model model-id
                                                :entity entity})}))

      :else
      {:ok? true :skipped? true})))

(defn ingest!
  "Ingest open-world data.

   Inputs:
   - entities (vector)
   - relations (vector)
   - require-model? (boolean, optional) => enforce registry presence

   Returns {:ok? true :counts {...}} or throws.
  "
  [{:keys [entities relations require-model?]}]
  (doseq [e entities]
    (ent/validate-entity e))
  (let [model-results (map (fn [entity]
                             (validate-model {:entity entity
                                              :require-model? require-model?}))
                           entities)
        model-validated (count (remove :skipped? model-results))
        model-missing (count (filter :skipped? model-results))]
    (when (seq relations)
      (ent/validate-relations {:entities entities :relations relations}))
    {:ok? true
     :counts {:entities (count entities)
              :relations (count relations)
              :models-validated model-validated
              :models-missing model-missing}}))
