(ns futon1a.model.registry
  "Model registry (stub).")

(defonce registry (atom {}))

(defn register-model!
  "Register a model descriptor.

   Inputs:
   - id (keyword)
   - descriptor (map)
  "
  [{:keys [id descriptor]}]
  (when-not (keyword? id)
    (throw (ex-info "model id must be keyword" {:id id})))
  (swap! registry assoc id descriptor)
  {:ok? true :id id})

(defn get-model
  [id]
  (get @registry id))

(defn list-models
  []
  (keys @registry))
