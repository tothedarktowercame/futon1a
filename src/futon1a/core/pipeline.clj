(ns futon1a.core.pipeline
  "Cross-layer write pipeline (stub)."
  (:require [futon1a.auth.penholder :as auth]
            [futon1a.core.entity :as ent]
            [futon1a.core.identity :as id]
            [futon1a.core.xtdb :as xt]
            [futon1a.model.validation :as mv]))

(defn run-write!
  "Run a write through Layers 4 → 0.

   Inputs (map):
   - store
   - penholder
   - allowed-penholders (set)
   - model (map)
   - required-keys (set)
   - identity (map)
   - tx-ops (vector)
   - claim, detail (any)

   Returns {:ok? true :tx-id <string> :path <proof-path>} or throws.
  "
  [{:keys [store penholder allowed-penholders model required-keys identity tx-ops claim detail]}]
  (auth/authorize! {:penholder penholder :allowed-penholders allowed-penholders})
  (mv/validate-model! {:model model :required-keys (or required-keys #{})})
  (when identity
    (id/validate-identity identity))
  (when (or (:entity/id model) (:entity/type model))
    (ent/validate-entity model))
  (when (or (:relation/id model) (:relation/from model) (:relation/to model))
    (ent/validate-relation model))
  (when-not (vector? tx-ops)
    (throw (ex-info "tx-ops required"
                    {:error (xt/layer0-error :missing-tx-ops
                                             {:tx-ops tx-ops})})))
  (let [{:keys [tx-id path]} (xt/durable-write-tx!
                              {:store store
                               :actor penholder
                               :claim (or claim {:op :write})
                               :detail (or detail {:layer :pipeline})
                               :tx-ops tx-ops})]
    {:ok? true
     :tx-id tx-id
     :path path}))
