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
            [futon1a.model.type-registry :as types]
            [futon1a.model.expansion :as expansion]
            [futon1a.model.validation :as mv]))

(defn- tx-ops->docs
  [tx-ops]
  (->> tx-ops
       (keep (fn [op]
               (when (and (vector? op) (= :xtdb.api/put (first op)) (map? (second op)))
                 (second op))))
       (vec)))

(defn- infer-xt-id-for-identity
  "Infer a single :xt/id from tx-ops docs when the caller supplied an identity map.

   We keep this strict: identity mapping must point to exactly one document id.
   Without this, we'd be unable to write a coherent (source, external-id) -> entity-id mapping.
  "
  [tx-ops]
  (let [docs (tx-ops->docs tx-ops)
        ids (->> docs (keep :xt/id) (distinct) (vec))]
    (cond
      (= 1 (count ids)) (first ids)
      (empty? ids) (throw (ex-info "identity requires :xt/id in tx-ops puts"
                                   {:error (mv/layer4-error :identity-missing-xt-id
                                                            {:tx-ops/put-docs (count docs)})}))
      :else (throw (ex-info "identity requires exactly one :xt/id"
                            {:error (mv/layer4-error :identity-multiple-xt-ids
                                                     {:xt/ids ids})})))))

(defn- resolve-proof-log-path
  [store explicit-proof-log-path]
  (or explicit-proof-log-path
      (:proof-log-path (meta store))))

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
           expansion allowed-expansions tooling allowed-tooling proof-log-path]}]
  ;; Layer 4 — model validation (400)
  (expansion/expansion-gate! {:expansion expansion
                              :allowed-expansions (or allowed-expansions #{})})
  (mv/validate-model! {:model model :required-keys (or required-keys #{})})
  (when-not (vector? tx-ops)
    (throw (ex-info "tx-ops required"
                    {:error (mv/layer4-error :missing-tx-ops
                                             {:tx-ops tx-ops})})))
  (when-not (every? vector? tx-ops)
    (throw (ex-info "tx-ops must be a vector of XTDB op vectors"
                    {:error (mv/layer4-error :invalid-tx-ops
                                             {:expected :vector-of-vectors
                                              :sample (take 3 tx-ops)})})))
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
  (let [identity (when (map? identity) identity)
        ext? (and identity (or (:external-id identity) (:source identity)))
        ;; If any external identity key is present, require both.
        _ (when ext?
            (when-not (and (:external-id identity) (:source identity))
              (throw (ex-info "identity requires :source and :external-id"
                              {:error (mv/layer4-error :identity-missing-required
                                                       {:identity identity
                                                        :required #{:source :external-id}})}))))
        ;; If using external identity, bind it to exactly one :xt/id from tx-ops puts.
        xt-id (when ext? (infer-xt-id-for-identity tx-ops))
        idx-id (when ext? (id/external-index-id {:source (:source identity)
                                                 :external-id (:external-id identity)}))
        existing-by-external (when idx-id (xt/entity store idx-id))
        _ (when identity
            (id/validate-identity (cond-> identity
                                    xt-id (assoc :id (str xt-id))
                                    idx-id (assoc :existing-by-external
                                                  ;; Allow idempotent writes: mapping to same entity-id is OK.
                                                  (when (and existing-by-external
                                                             (not= (str xt-id) (str (:identity/entity-id existing-by-external))))
                                                    existing-by-external)))))
        identity-op (when (and ext? idx-id)
                      (let [doc (id/external-index-doc {:source (:source identity)
                                                        :external-id (:external-id identity)
                                                        :entity-id (str xt-id)})]
                        (when doc
                          [:xtdb.api/put doc])))
        ;; Always ensure the index doc is present on success, even when idempotent.
        tx-ops (cond-> (vec tx-ops)
                 identity-op (conj identity-op))
        ;; Layer 0 — durable write (503)
        docs (tx-ops->docs tx-ops)
        type-ops (types/tx-ops-for-docs docs)
        tx-ops (into (vec type-ops) tx-ops)
        {:keys [tx-id path]} (xt/durable-write-tx!
                              {:store store
                               :actor penholder
                               :claim (or claim {:op :write})
                               :detail (or detail {:layer :pipeline})
                               :tx-ops tx-ops
                               :proof-log-path (resolve-proof-log-path store proof-log-path)})]
    {:ok? true
     :tx-id tx-id
     :path/id (get-in path [:path/id])
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
           expansion allowed-expansions proof-log-path]}]
  (expansion/expansion-gate! {:expansion expansion
                              :allowed-expansions (or allowed-expansions #{})})
  (when-not (vector? tx-ops)
    (throw (ex-info "tx-ops required"
                    {:error (mv/layer4-error :missing-tx-ops
                                             {:tx-ops tx-ops})})))
  (when-not (every? vector? tx-ops)
    (throw (ex-info "tx-ops must be a vector of XTDB op vectors"
                    {:error (mv/layer4-error :invalid-tx-ops
                                             {:expected :vector-of-vectors
                                              :sample (take 3 tx-ops)})})))
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
        docs (tx-ops->docs tx-ops)
        type-ops (types/tx-ops-for-docs docs)
        tx-ops (into (vec type-ops) tx-ops)
        {:keys [tx-id path]} (xt/durable-write-tx!
                              {:store store
                               :actor penholder
                               :claim (or claim {:op :ingest})
                               :detail (or detail {:layer :ingest})
                               :tx-ops tx-ops
                               :proof-log-path (resolve-proof-log-path store proof-log-path)})]
    {:ok? true
     :counts (:counts ingest)
     :tx-id tx-id
     :path/id (get-in path [:path/id])
     :path path}))
