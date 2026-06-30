(ns futon1a.core.pipeline
  "Cross-layer write pipeline.

   Executes gates in strict layer order: L4 → L3 → L2 → L1 → L0.
   If layer N fails, layers below N never run.

   Invariant: I3 (Hierarchy — errors surface at the layer that caused them).
   Pattern:   storage/error-layer-hierarchy
   Theory:    futon-theory/error-hierarchy, futon-theory/stop-the-line"
  (:require [clojure.string :as str]
            [futon1a.auth.penholder :as auth]
            [futon1a.core.entity :as ent]
            [futon1a.core.identity :as id]
            [futon1a.core.invariants :as inv]
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
           expansion allowed-expansions tooling allowed-tooling proof-log-path
           counter-ratchet]}]
  ;; Layer 4 — model validation (400)
  (expansion/expansion-gate! {:expansion expansion
                              :allowed-expansions (or allowed-expansions #{})})
  (mv/validate-model! {:model model :required-keys (or required-keys #{})})
  ;; L4 canonical-id gate (E-futon1a-archivist): entity writes only; no-op for
  ;; types without an :id-pattern descriptor. Gates the /entity compat path that
  ;; the watcher/capability-ingest/agents use (open-world/ingest! is only /ingest).
  (when (:entity/type model)
    (open-world/gate-entity-id! model))
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
        _ (inv/enforce-counter-ratchet! {:store store
                                         :tx-ops tx-ops
                                         :allow-drop-classes (:allow-drop-classes (or counter-ratchet {}))})
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

;; ---------------------------------------------------------------------------
;; Erasure — the GDPR right-to-erasure path (first-class, gated, audited).
;; ---------------------------------------------------------------------------

(def erase-allowed-penholders
  "Erasure is destructive (XTDB evict removes a doc AND its whole bitemporal
   history). Gated to the operator only (Joe, 2026-06-26) — there is no API
   surface; it runs via the CLI as penholder \"joe\"."
  #{"joe"})

(defn- sha256-hex [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes (str s) "UTF-8"))))))

(defn run-erase!
  "GDPR right-to-erasure: hard-delete docs by `:eids` via XTDB **evict** — which
   removes each doc AND its entire bitemporal history (unlike `:delete`, which
   only tombstones, leaving the data recoverable via `db-as-of`). This is the
   one sanctioned destructive path; it rides the durable pipeline rather than a
   raw evict:

     L4 validate (non-empty eids + a reason) → L3 authorize (penholder must be
     in `erase-allowed-penholders` = #{\"joe\"}) → L0 durable evict, with the
     counter-ratchet permitting the deliberate drop (`:allow-drop-classes`).

   Emits a **non-revealing compliance audit record** — an `:erasure-event`
   entity carrying actor · reason · count · SHA-256 of the sorted eids · time
   (NOT the erased content, so the receipt itself isn't a re-leak). The audit
   put is materialization-verified by L0; the evicts are not (they remove).

   Inputs (map): `:store` `:penholder` `:eids` (vector of xt/id) `:reason`
   (string) `:detail` (optional). Returns
   `{:ok? true :erased N :audit-id <id> :tx-id <id>}` or throws.

   NOTE (named follow-ons): for LIVE data, eviction alone doesn't hold — the
   watcher / ingest would re-derive it; a re-ingestion suppression list is the
   GDPR-subject generalisation. For the empty noise type-docs this excursion
   targets, no live source carries them, so eviction sticks."
  [{:keys [store penholder eids reason detail]}]
  ;; L4 — validation
  (when-not (and (vector? eids) (seq eids))
    (throw (ex-info "erase requires a non-empty :eids vector"
                    {:error (mv/layer4-error :erase-missing-eids {:eids eids})})))
  (when-not (and (string? reason) (seq (str/trim reason)))
    (throw (ex-info "erase requires a :reason (for the GDPR audit record)"
                    {:error (mv/layer4-error :erase-missing-reason {})})))
  ;; L3 — authorization (erasure is operator-only)
  (auth/authorize! {:penholder penholder :allowed-penholders erase-allowed-penholders})
  (let [eids      (vec (distinct (map str eids)))
        audit-id  (str "erasure-event/" (java.util.UUID/randomUUID))
        audit     {:xt/id audit-id
                   :entity/id audit-id
                   :entity/type :erasure-event
                   :name (str "erasure of " (count eids) " doc(s)")
                   :erasure/event? true
                   :erasure/by penholder
                   :erasure/reason reason
                   :erasure/eid-count (count eids)
                   :erasure/eid-sha256 (sha256-hex (pr-str (sort eids)))
                   :erasure/at (str (java.time.Instant/now))}
        type-ops  (types/tx-ops-for-docs [audit])
        evict-ops (mapv (fn [eid] [:xtdb.api/evict eid]) eids)
        tx-ops    (-> (vec type-ops) (conj [:xtdb.api/put audit]) (into evict-ops))
        _ (inv/enforce-counter-ratchet!
           {:store store :tx-ops tx-ops
            ;; erasure is a deliberate drop — permit every protected class
            :allow-drop-classes #{:entity :relation :descriptor :docbook}})
        {:keys [tx-id]} (xt/durable-write-tx!
                         {:store store :actor penholder
                          :claim {:op :erase :count (count eids)}
                          :detail (or detail {:layer :erase :reason reason})
                          :tx-ops tx-ops
                          :proof-log-path (resolve-proof-log-path store nil)})]
    {:ok? true :erased (count eids) :audit-id audit-id :tx-id tx-id}))

(defn retract-type!
  "Erase a type-doc from the registry — every kind/encoding variant currently
   present (via `types/type-xt-ids-present`). Sugar over `run-erase!` (so it is
   joe-gated + audited). For the empty noise docs this sticks: no live domain
   doc carries the type, so the derive-from-data registry won't recreate it.

   Inputs: `:store` `:node` `:type-id` `:penholder` `:reason` (optional).
   Returns `run-erase!`'s result, or `{:ok? true :erased 0}` if no variant
   is present."
  [{:keys [store node type-id penholder reason]}]
  (let [eids (types/type-xt-ids-present node type-id)]
    (if (seq eids)
      (run-erase! {:store store :penholder penholder :eids eids
                   :reason (or reason (str "retract-type " type-id))})
      {:ok? true :erased 0 :note "no variants present"})))

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
           expansion allowed-expansions proof-log-path counter-ratchet]}]
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
        _ (inv/enforce-counter-ratchet! {:store store
                                         :tx-ops tx-ops
                                         :allow-drop-classes (:allow-drop-classes (or counter-ratchet {}))})
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
