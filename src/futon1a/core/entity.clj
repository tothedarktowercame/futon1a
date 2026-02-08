(ns futon1a.core.entity
  "Layer 2 entity/relation integrity checks." )

(defn- layer2-error
  [reason context]
  {:error/layer 2
   :error/status 500
   :error/reason reason
   :error/context context})

(defn- ensure-id
  [m k]
  (let [v (get m k)]
    (when-not v
      (throw (ex-info "missing id" {:error (layer2-error :missing-id {:field k :entity m})})))
    v))

(defn validate-entity
  "Validate a single entity map. Requires :entity/id and :entity/type." 
  [entity]
  (ensure-id entity :entity/id)
  (ensure-id entity :entity/type)
  {:ok? true :entity entity})

(defn validate-relation
  "Validate a relation map. Requires :relation/id, :relation/from, :relation/to." 
  [relation]
  (ensure-id relation :relation/id)
  (ensure-id relation :relation/from)
  (ensure-id relation :relation/to)
  {:ok? true :relation relation})

(defn validate-relations
  "Validate all relations and ensure endpoints exist in entities.

   Inputs:
   - entities: vector of entity maps (must include :entity/id)
   - relations: vector of relation maps

   Returns {:ok? true} or throws.
  "
  [{:keys [entities relations]}]
  (let [entity-ids (set (map :entity/id entities))]
    (doseq [rel relations]
      (validate-relation rel)
      (let [from (:relation/from rel)
            to (:relation/to rel)]
        (when-not (contains? entity-ids from)
          (throw (ex-info "relation endpoint missing"
                          {:error (layer2-error :missing-endpoint
                                                {:relation rel :missing from})})))
        (when-not (contains? entity-ids to)
          (throw (ex-info "relation endpoint missing"
                          {:error (layer2-error :missing-endpoint
                                                {:relation rel :missing to})})))))
    {:ok? true}))
