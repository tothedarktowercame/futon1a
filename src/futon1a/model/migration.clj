(ns futon1a.model.migration
  "Schema evolution migration registry."
  (:require [futon1a.model.validation :as mv]))

(defonce migrations (atom {}))

(defn clear-migrations!
  []
  (reset! migrations {}))

(defn register-migration!
  "Register a migration step.

   Inputs:
   - model-id (keyword)
   - from (int)
   - to (int)
   - migrate-fn (fn [payload] -> payload)
  "
  [{:keys [model-id from to migrate-fn]}]
  (when-not (keyword? model-id)
    (throw (ex-info "model-id must be keyword" {:model-id model-id})))
  (when-not (and (int? from) (int? to))
    (throw (ex-info "from/to must be ints" {:from from :to to})))
  (when-not (fn? migrate-fn)
    (throw (ex-info "migrate-fn required" {:model-id model-id})))
  (swap! migrations assoc-in [model-id [from to]] migrate-fn)
  {:ok? true :model-id model-id :from from :to to})

(defn migrate!
  "Apply a registered migration.

   Inputs:
   - model-id (keyword)
   - from (int)
   - to (int)
   - payload (map)
  "
  [{:keys [model-id from to payload]}]
  (let [migrate-fn (get-in @migrations [model-id [from to]])]
    (when-not migrate-fn
      (throw (ex-info "migration not found"
                      {:error (mv/layer4-error :missing-migration
                                               {:model-id model-id
                                                :from from
                                                :to to})})))
    {:ok? true
     :model-id model-id
     :from from
     :to to
     :payload (migrate-fn payload)}))
