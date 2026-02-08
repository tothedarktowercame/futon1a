(ns futon1a.scripts.backfill-missing-component-sources
  "Backfill :entity/source on pattern/component entities referenced by :pattern/includes.

   Futon3's parity tests require every included component to have non-empty source.
   Some parsed sections are effectively empty, producing component docs without
   :entity/source. This script repairs those deterministically."
  (:require [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.core.xtdb-node :as xtnode]
            [futon1a.system :as system]
            [xtdb.api :as xtdb]))

(defn- sigiled-pattern-entities
  [node]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [(pull e [*])]
                      :where [[e :entity/type :pattern/library]
                              [r :relation/type :pattern/has-sigil]
                              [r :relation/src e]
                              [r :relation/dst s]
                              [s :entity/type :pattern/sigil]]})
         (map first)
         (vec))))

(defn- outgoing-relations
  [node pattern-eid]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [(pull r [*])]
                      :in [src]
                      :where [[r :relation/type _]
                              [r :relation/src src]]}
                 pattern-eid)
         (map first)
         (vec))))

(defn- includes-relation?
  [rel]
  (let [t (:relation/type rel)
        prov (:relation/provenance rel)
        note (when (map? prov) (:note prov))]
    (or (= t :pattern/includes)
        (and (= t :arxana/scholium)
             (or (= note ":pattern/includes")
                 (= note "pattern/includes"))))))

(defn- blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- fill-source
  "Deterministic fallback source text for empty component sources."
  [component-doc]
  (or (some-> (:entity/external-id component-doc) str/trim not-empty)
      (some-> (:entity/name component-doc) str/trim not-empty)
      "missing component source"))

(defn backfill!
  "Backfill missing :entity/source for included components under sigiled patterns.

   Returns {:ok? true :checked N :patched M :tx-ops K}."
  [{:keys [node store penholder allowed-penholders] :as ctx}]
  (when-not (and node store)
    (throw (ex-info "node and store required" {:ctx (keys ctx)})))
  (let [db (xtdb/db node)
        patterns (sigiled-pattern-entities node)
        patched (->> patterns
                     (mapcat (fn [p]
                               (let [pid (str (:entity/id p))
                                     rels (outgoing-relations node pid)]
                                 (->> rels
                                      (filter includes-relation?)
                                      (keep :relation/dst)
                                      (map str)
                                      (distinct)
                                      (keep (fn [cid]
                                              (when-let [doc (xtdb/entity db cid)]
                                                (when (blank? (:entity/source doc))
                                                  (assoc doc :entity/source (fill-source doc))))))))))
                     ;; De-dupe by xt/id to avoid redundant puts.
                     (reduce (fn [acc doc] (assoc acc (str (:xt/id doc)) doc)) {})
                     (vals)
                     (vec))
        tx-ops (mapv (fn [doc] [:xtdb.api/put doc]) patched)]
    (when (seq tx-ops)
      (pipeline/run-write! {:store store
                            :penholder penholder
                            :allowed-penholders (or allowed-penholders #{})
                            :model {:id 1}
                            :required-keys #{:id}
                            :identity nil
                            :tx-ops tx-ops
                            :claim {:op :backfill/missing-component-sources}
                            :detail {:checked (count patterns)
                                     :patched (count patched)}}))
    {:ok? true
     :checked (count patterns)
     :patched (count patched)
     :tx-ops (count tx-ops)}))

(defn -main
  [& _args]
  (let [data-dir (System/getenv "FUTON1A_DATA_DIR")]
    (when-not (and (string? data-dir) (seq (str/trim data-dir)))
      (throw (ex-info "FUTON1A_DATA_DIR required" {})))
    (let [node (xtnode/start-node! (system/xtdb-config {:data-dir data-dir}))
          store (xtnode/xtdb-store node)]
      (try
        (let [ph (or (some-> (System/getenv "FUTON1A_COMPAT_PENHOLDER") str/trim not-empty)
                     "cli")]
          (println (pr-str (backfill! {:node node
                                       :store store
                                       :penholder ph
                                       :allowed-penholders #{ph}}))))
        (finally
          (xtnode/stop-node! node))))))
