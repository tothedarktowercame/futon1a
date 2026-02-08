(ns futon1a.core.pipeline
  "Cross-layer write pipeline.

   Executes gates in strict layer order: L4 → L3 → L2 → L1 → L0.
   If layer N fails, layers below N never run.

   Invariant: I3 (Hierarchy — errors surface at the layer that caused them).
   Pattern:   storage/error-layer-hierarchy
   Theory:    futon-theory/error-hierarchy, futon-theory/stop-the-line"
  (:require [futon1a.auth.penholder :as auth]
            [futon1a.core.entity :as ent]
            [futon1a.core.identity :as id]
            [futon1a.core.xtdb :as xt]
            [futon1a.ingest.open-world :as open-world]
            [futon1a.model.expansion :as expansion]
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
  [{:keys [store penholder allowed-penholders model required-keys identity tx-ops claim detail
           expansion allowed-expansions tooling allowed-tooling]}]
  ;; Layer 4 — model validation (400)
  (expansion/expansion-gate! {:expansion expansion
                              :allowed-expansions (or allowed-expansions #{})})
  (mv/validate-model! {:model model :required-keys (or required-keys #{})})
  (when-not (vector? tx-ops)
    (throw (ex-info "tx-ops required"
                    {:error (mv/layer4-error :missing-tx-ops
                                             {:tx-ops tx-ops})})))
  ;; Layer 3 — authorization (403)
  (if tooling
    (auth/authorize-tooling! {:penholder penholder
                              :allowed-penholders allowed-penholders
                              :tooling-id (:tooling-id tooling)
                              :allowed-tooling allowed-tooling})
    (auth/authorize! {:penholder penholder
                      :allowed-penholders allowed-penholders}))
  ;; Layer 2 — entity/relation integrity (500)
  (when (or (:entity/id model) (:entity/type model))
    (ent/validate-entity model))
  (when (or (:relation/id model) (:relation/from model) (:relation/to model))
    (ent/validate-relation model))
  ;; Layer 1 — identity uniqueness (409)
  (when identity
    (id/validate-identity identity))
  ;; Layer 0 — durable write (503)
  (let [{:keys [tx-id path]} (xt/durable-write-tx!
                              {:store store
                               :actor penholder
                               :claim (or claim {:op :write})
                               :detail (or detail {:layer :pipeline})
                               :tx-ops tx-ops})]
    {:ok? true
     :tx-id tx-id
     :path path}))

(defn run-open-world!
  "Run an open-world ingest through L4 → L3 → L2 → L0.

   Inputs:
   - store
   - penholder
   - allowed-penholders (set)
   - tooling (map, optional)
   - allowed-tooling (set, optional)
   - entities (vector)
   - relations (vector)
   - require-model? (boolean, optional)
   - tx-ops (vector)
   - claim, detail (any)

   Returns {:ok? true :counts {...} :tx-id <string> :path <proof-path>} or throws.
  "
  [{:keys [store penholder allowed-penholders tooling allowed-tooling
           entities relations require-model? tx-ops claim detail
           expansion allowed-expansions]}]
  (expansion/expansion-gate! {:expansion expansion
                              :allowed-expansions (or allowed-expansions #{})})
  (when-not (vector? tx-ops)
    (throw (ex-info "tx-ops required"
                    {:error (mv/layer4-error :missing-tx-ops
                                             {:tx-ops tx-ops})})))
  (if tooling
    (auth/authorize-tooling! {:penholder penholder
                              :allowed-penholders allowed-penholders
                              :tooling-id (:tooling-id tooling)
                              :allowed-tooling allowed-tooling})
    (auth/authorize! {:penholder penholder
                      :allowed-penholders allowed-penholders}))
  (let [ingest (open-world/ingest! {:entities entities
                                    :relations relations
                                    :require-model? require-model?})
        {:keys [tx-id path]} (xt/durable-write-tx!
                              {:store store
                               :actor penholder
                               :claim (or claim {:op :ingest})
                               :detail (or detail {:layer :ingest})
                               :tx-ops tx-ops})]
    {:ok? true
     :counts (:counts ingest)
     :tx-id tx-id
     :path path}))
