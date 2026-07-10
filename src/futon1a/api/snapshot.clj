(ns futon1a.api.snapshot
  "Snapshot export/restore for graph docs (entities + relations).

   This supports futon4's `arxana-store` `/snapshot` and `/snapshot/restore`
   helpers used by legacy scholia save/restore workflows.

   Design:
   - Snapshot is a filesystem artifact under <data-dir>/snapshots/.
   - Snapshot contains only graph docs (docs that look like entities or relations).
   - Restore re-ingests those docs through the open-world pipeline, so L0-L3
     durability/auth gates still apply (no direct XTDB bypass).

   This is intentionally conservative and dev-focused: it is a convenience for
   QA/rollback and not a generalized backup mechanism."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.core.invariants :as inv]
            [futon1a.model.validation :as mv]
            [xtdb.api :as xtdb]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- ensure-dir! [^String dir]
  (let [f (io/file dir)]
    (.mkdirs f)
    (.getAbsolutePath f)))

(defn- snapshots-root
  [data-dir]
  (str (ensure-dir! (str (str/replace (or data-dir "") #"/+$" "") "/snapshots"))))

(defn- snapshot-file
  [data-dir snapshot-id]
  (str (io/file (snapshots-root data-dir) (str snapshot-id ".edn"))))

(defn- edn-safe
  "Convert values that don't round-trip through `clojure.edn/read-string`."
  [v]
  (walk/postwalk
   (fn [x]
     (cond
       (instance? java.time.Instant x)
       (tagged-literal 'futon1a/instant (str x))

       :else x))
   v))

(def ^:private edn-readers
  {'futon1a/instant (fn [s] (java.time.Instant/parse (str s)))})

(defn- list-graph-doc-ids
  "Return a vector of XTDB entity ids for docs that look like graph docs."
  [node]
  (let [db (xtdb/db node)
        q-ent '{:find [e]
                :where [[e :entity/id]]}
        q-rel '{:find [e]
                :where [[e :relation/id]]}
        ent-ids (mapv first (xtdb/q db q-ent))
        rel-ids (mapv first (xtdb/q db q-rel))]
    (->> (concat ent-ids rel-ids)
         (distinct)
         (vec))))

(defn- list-hyperedge-doc-ids
  "Return a vector of XTDB entity ids for hyperedge docs (docs with :hx/id).

  Uses a LAZY `open-q` cursor, NOT `q`. There are ~246k hyperedges, and a
  materializing `q` over all of them (or any `:order-by`) hits XTDB's ~30s
  server-side query timeout under live load — verified: `q` id-scan and any
  `:order-by`+`:limit` both time out at 30s, while `open-q` streams 20k ids in
  ~1.4s from the index without materializing/sorting the whole result. This is
  the id-iteration primitive that makes the full hyperedge export possible.
  (Also avoids the per-type `(count e)` census timeout entirely.) Added for
  E-futon1a-to-futon1b-migration-pipeline (full-store hyperedge export)."
  [node]
  (let [db (xtdb/db node)]
    (with-open [c (xtdb/open-q db '{:find [e] :where [[e :hx/id]]})]
      (into [] (map first) (iterator-seq c)))))

(defn- list-evidence-doc-ids
  "Return a vector of XTDB entity ids for evidence docs (docs with
  :evidence/id). Same lazy `open-q` idiom as list-hyperedge-doc-ids (see its
  docstring for why `q`/`:order-by` are unusable at this scale). Added for
  E-futon1a-to-futon1b-migration-pipeline F5: the session-scoped evidence
  export misses SESSIONLESS evidence (docs with no :evidence/session-id,
  e.g. mission-sync records) — this scope drains ALL evidence by id."
  [node]
  (let [db (xtdb/db node)]
    (with-open [c (xtdb/open-q db '{:find [e] :where [[e :evidence/id]]})]
      (into [] (map first) (iterator-seq c)))))

(defn export-graph-snapshot
  "Export current graph docs to an EDN snapshot file.

  Returns {:ok? true :snapshot/id ... :snapshot/file ... :counts {...}}."
  [{:keys [node data-dir scope label] :as _req}]
  (when-not (and node data-dir)
    (throw (ex-info "snapshot requires node and data-dir"
                    {:error (mv/layer4-error :missing-required
                                             {:required #{:node :data-dir}})})))
  (let [scope (or scope "all")
        scope (if (keyword? scope) (name scope) (str scope))
        _ (when-not (#{"all" "latest" "hyperedges" "evidence"} scope)
            (throw (ex-info "invalid snapshot scope"
                            {:error (mv/layer4-error :invalid-snapshot-scope
                                                     {:scope scope
                                                      :expected ["all" "latest" "hyperedges" "evidence"]})})))
        hx? (= scope "hyperedges")
        ev? (= scope "evidence")
        snapshot-id (cond
                      hx?               "hyperedges"
                      ev?               "evidence"
                      (= scope "latest") "latest"
                      :else             (str "snap-" (now-ms)))
        ids (cond
              hx? (list-hyperedge-doc-ids node)
              ev? (list-evidence-doc-ids node)
              :else (list-graph-doc-ids node))
        db (xtdb/db node)
        docs (->> ids
                  (mapv (fn [id] (xtdb/entity db id)))
                  (remove nil?)
                  (vec))
        counts (cond
                 hx? {:docs (count docs)
                      :ids (count ids)
                      :hyperedges (count (filter :hx/id docs))}
                 ev? {:docs (count docs)
                      :ids (count ids)
                      :evidence (count (filter :evidence/id docs))
                      :sessionless (count (remove :evidence/session-id docs))}
                 :else {:docs (count docs)
                        :ids (count ids)
                        :entities (count (filter :entity/id docs))
                        :relations (count (filter :relation/id docs))})
        file (snapshot-file data-dir snapshot-id)
        payload {:snapshot/id snapshot-id
                 :snapshot/scope scope
                 :snapshot/label label
                 :snapshot/created-at (now-ms)
                 :counts counts
                 :docs docs}]
    (ensure-dir! (snapshots-root data-dir))
    (with-open [w (io/writer file)]
      (binding [*out* w
                *print-readably* true]
        (pr (edn-safe payload))))
    {:ok? true
     :snapshot/id snapshot-id
     :snapshot/file file
     :counts counts}))

(defn- read-snapshot!
  [data-dir snapshot-id]
  (let [file (snapshot-file data-dir snapshot-id)
        f (io/file file)]
    (when-not (.exists f)
      (throw (ex-info "snapshot not found"
                      {:error (inv/layer2-error :snapshot-missing
                                               {:snapshot/id snapshot-id
                                                :snapshot/file file})})))
    (let [data (edn/read-string {:readers edn-readers} (slurp file))]
      (when-not (map? data)
        (throw (ex-info "snapshot malformed"
                        {:error (inv/layer2-error :snapshot-malformed
                                                 {:snapshot/id snapshot-id
                                                  :snapshot/file file})})))
      (assoc data :snapshot/file file))))

(defn restore-graph-snapshot!
  "Restore (merge) graph docs from snapshot into XTDB via open-world pipeline.

  Returns {:ok? true :tx-id ... :counts {...}}."
  [{:keys [store penholder allowed-penholders data-dir scope payload snapshot-id] :as req}]
  (when-not (and store penholder allowed-penholders data-dir)
    (throw (ex-info "restore requires store, penholder, allowed-penholders, data-dir"
                    {:error (mv/layer4-error :missing-required
                                             {:required #{:store :penholder :allowed-penholders :data-dir}})})))
  (let [scope (or scope "all")
        scope (if (keyword? scope) (name scope) (str scope))
        snapshot-id (or (:snapshot/id req)
                        (when (map? payload) (:snapshot/id payload))
                        snapshot-id
                        (when (= scope "latest") "latest"))]
    (when-not (and (string? snapshot-id) (seq (str/trim snapshot-id)))
      (throw (ex-info "snapshot/id required"
                      {:error (mv/layer4-error :missing-required
                                               {:required #{:snapshot/id}
                                               :scope scope})})))
    (let [{:keys [docs] :as snapshot} (read-snapshot! data-dir snapshot-id)
          docs (or docs [])
          docs (if (sequential? docs) (vec docs) [])
          docs (->> docs (filter map?) (mapv (fn [d] (if (:xt/id d) d (assoc d :xt/id (:xt/id d))))))
          entity-docs (filterv :entity/id docs)
          relation-docs (filterv :relation/id docs)
          entities (mapv (fn [d]
                           (when-not (and (:entity/id d) (:entity/type d))
                             (throw (ex-info "entity doc missing id/type"
                                             {:error (mv/layer4-error :invalid-snapshot-doc
                                                                      {:kind :entity
                                                                       :doc (select-keys d [:xt/id :entity/id :entity/type])})})))
                           {:entity/id (:entity/id d)
                            :entity/type (:entity/type d)})
                         entity-docs)
          relations (mapv (fn [d]
                            (when-not (and (:relation/id d) (:relation/type d) (:relation/from d) (:relation/to d))
                              (throw (ex-info "relation doc missing required keys"
                                              {:error (mv/layer4-error :invalid-snapshot-doc
                                                                       {:kind :relation
                                                                        :doc (select-keys d [:xt/id :relation/id :relation/type :relation/from :relation/to])})})))
                            {:relation/id (:relation/id d)
                             :relation/type (:relation/type d)
                             :relation/from (:relation/from d)
                             :relation/to (:relation/to d)})
                          relation-docs)
          tx-ops (mapv (fn [d] [:xtdb.api/put (if (:xt/id d) d (assoc d :xt/id (:xt/id d)))]) docs)
          result (pipeline/run-open-world!
                  (merge (select-keys req [:allowed-expansions :allowed-tooling])
                         {:store store
                          :penholder penholder
                          :allowed-penholders allowed-penholders
                          :entities entities
                          :relations relations
                          :require-model? false
                          :tx-ops tx-ops
                          :claim {:op :snapshot/restore}
                          :detail {:snapshot/id snapshot-id
                                   :snapshot/file (:snapshot/file snapshot)
                                   :counts {:docs (count docs)
                                            :entities (count entities)
                                            :relations (count relations)}}}))]
      {:ok? true
       :tx-id (:tx-id result)
       :path/id (get-in result [:path :path/id])
       :counts (:counts result)
       :snapshot/id snapshot-id})))
