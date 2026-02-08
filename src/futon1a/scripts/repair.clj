(ns futon1a.scripts.repair
  "Repair/backfill tools.

   Tension:  Invariant enforcement vs operational repair (T7).
   Pattern:  storage/invariants-vs-repair"
  (:require [futon1a.core.invariants :as inv]))

(defn repair-entities!
  "Apply repair steps to entities.

   Inputs:
   - entities (vector)
   - repair-fn (fn [entity] => entity), default identity
   - dry-run? (boolean, optional)

   Returns {:ok? boolean :counts {...} :repaired [...] :errors [...]}."
  [{:keys [entities repair-fn dry-run?]}]
  (when-not (sequential? entities)
    (throw (ex-info "entities must be sequential" {:entities entities})))
  (let [repair-fn (or repair-fn identity)]
    (if dry-run?
      {:ok? true
       :dry-run? true
       :counts {:entities (count entities)
                :repaired 0
                :errors 0}
       :repaired []
       :errors []}
      (let [{:keys [repaired errors]}
            (reduce (fn [{:keys [repaired errors]} entity]
                      (try
                        {:repaired (conj repaired (repair-fn entity))
                         :errors errors}
                        (catch Exception e
                          {:repaired repaired
                           :errors (conj errors {:entity entity
                                                 :error (.getMessage e)})})))
                    {:repaired [] :errors []}
                    entities)]
        {:ok? (empty? errors)
         :counts {:entities (count entities)
                  :repaired (count repaired)
                  :errors (count errors)}
         :repaired repaired
         :errors errors}))))

(defn verify-repair!
  "Verify that repair actions restore invariants.
   Currently invokes counter-ratchet and checks for repair errors." 
  [{:keys [prev next label errors]}]
  (when (seq errors)
    (throw (ex-info "repair errors present"
                    {:error (inv/layer2-error :repair-errors
                                              {:label label
                                               :errors errors})})))
  (inv/counter-ratchet {:prev prev :next next :label label}))
