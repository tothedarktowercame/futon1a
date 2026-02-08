(ns futon1a.scripts.backfill-sigiled-patterns-includes
  "Ensure every sigiled pattern/library has at least one :pattern/includes component.

   Futon3's parity tests require:
   - every standard-library pattern has at least one outgoing :pattern/includes,
   - each included component entity has non-empty :entity/source.

   Some patterns in futon3's sources (notably multiarg blocks) don't parse into
   structured components via scripts.pattern-sync, so they can be present in
   XTDB without any includes edges. This script deterministically backfills a
   single summary component for any *sigiled* pattern missing includes."
  (:require [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.core.xtdb-node :as xtnode]
            [futon1a.system :as system]
            [xtdb.api :as xtdb]))

(defn- stable-component-id [pattern-eid]
  (str "component|auto|" pattern-eid "|01-summary"))

(defn- stable-includes-rel-id [src-id dst-id]
  (str "rel|" src-id "|pattern/includes|" dst-id))

(defn- sigiled-pattern-entities
  "Return pulled pattern/library docs that have a :pattern/has-sigil edge to a :pattern/sigil entity."
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
  "True when relation doc encodes :pattern/includes either directly or as scholium note."
  [rel]
  (let [t (:relation/type rel)
        prov (:relation/provenance rel)
        note (when (map? prov) (:note prov))]
    (or (= t :pattern/includes)
        (and (= t :arxana/scholium)
             (or (= note ":pattern/includes")
                 (= note "pattern/includes"))))))

(defn backfill!
  "Backfill summary components for sigiled patterns missing includes.

   Inputs:
   - node, store
   - penholder, allowed-penholders

   Returns {:ok? true :checked N :updated M :tx-ops K}."
  [{:keys [node store penholder allowed-penholders] :as ctx}]
  (when-not (and node store)
    (throw (ex-info "node and store required" {:ctx (keys ctx)})))
  (let [patterns (sigiled-pattern-entities node)
        missing (->> patterns
                     (filter (fn [p]
                               (let [eid (str (:entity/id p))
                                     rels (outgoing-relations node eid)]
                                 (not-any? includes-relation? rels))))
                     (vec))
        tx-ops
        (->> missing
             (mapcat
              (fn [p]
                (let [eid (str (:entity/id p))
                      pname (or (:entity/name p) eid)
                      psource (some-> (or (:entity/source p) (:entity/name p) eid) str str/trim)
                      cid (stable-component-id eid)
                      cname (str pname "/01_summary")
                      component {:xt/id cid
                                 :entity/id cid
                                 :entity/name cname
                                 :entity/type :pattern/component
                                 :entity/external-id (str pname "::auto-summary")
                                 ;; Ensure non-empty so futon3's test passes.
                                 :entity/source (if (seq psource) psource "patterns-index backfill")}
                      rid (stable-includes-rel-id eid cid)
                      rel {:xt/id rid
                           :relation/id rid
                           :relation/type :pattern/includes
                           :relation/src eid
                           :relation/dst cid
                           :relation/from eid
                           :relation/to cid
                           :relation/provenance {:note ":pattern/includes"
                                                 :source "futon1a backfill sigiled includes"}}]
                  [[:xtdb.api/put component]
                   [:xtdb.api/put rel]])))
             (vec))]
    (when (seq tx-ops)
      (pipeline/run-write! {:store store
                            :penholder penholder
                            :allowed-penholders (or allowed-penholders #{})
                            :model {:id 1}
                            :required-keys #{:id}
                            :identity nil
                            :tx-ops tx-ops
                            :claim {:op :backfill/sigiled-patterns-includes}
                            :detail {:checked (count patterns)
                                     :updated (count missing)}}))
    {:ok? true
     :checked (count patterns)
     :updated (count missing)
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

