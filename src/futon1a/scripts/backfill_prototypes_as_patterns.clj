(ns futon1a.scripts.backfill-prototypes-as-patterns
  "Backfill Futon3 devmap prototypes into the patterns surface.

   Futon3's pattern catalog includes prototype IDs like f0/p0. Historically these
   existed as :devmap/prototype entities; Futon3's API consumers (and tests)
   expect them to be present as :pattern/library entities with at least:
   - one :pattern/includes component (with :entity/source text)
   - one :pattern/has-sigil relation (when prototype sigils exist)

   This script is an explicit migration step: it updates existing prototype
   entities in-place and adds the missing relations/entities deterministically."
  (:require [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.core.xtdb-node :as xtnode]
            [futon1a.system :as system]
            [xtdb.api :as xtdb]))

(defn- stable-component-id [entity-id]
  (str "component|prototype|" entity-id "|01-summary"))

(defn- stable-includes-rel-id [src-id dst-id]
  (str "rel|" src-id "|pattern/includes|" dst-id))

(defn- stable-sigil-rel-id [src-id dst-id]
  (str "rel|" src-id "|pattern/has-sigil|" dst-id))

(defn- prototype-entities
  [node]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [(pull e [*])]
                      :where [[e :entity/type :devmap/prototype]]})
         (map first)
         (vec))))

(defn- prototype-sigil-edges
  "Return pairs of [prototype-entity-id sigil-entity-id] from prototype/has-sigil relations."
  [node]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [src dst]
                      :where [[r :relation/type :prototype/has-sigil]
                              [r :relation/src src]
                              [r :relation/dst dst]]})
         (map (fn [[src dst]] [(str src) (str dst)]))
         (vec))))

(defn backfill!
  "Run the backfill using a running futon1a system context.

   Inputs:
   - node, store
   - penholder, allowed-penholders

   Returns {:ok? true :updated N :relations N}."
  [{:keys [node store penholder allowed-penholders] :as ctx}]
  (when-not (and node store)
    (throw (ex-info "node and store required" {:ctx (keys ctx)})))
  (let [protos (prototype-entities node)
        sigil-edges (prototype-sigil-edges node)
        by-id (into {} (map (fn [d] [(str (:entity/id d)) d])) protos)
        ;; Build tx ops.
        tx-ops
        (vec
         (concat
          ;; Update each prototype entity to be a pattern/library.
          (mapcat
           (fn [doc]
             (let [eid (str (:entity/id doc))
                   name (or (:entity/name doc) (:entity/id doc))
                   updated (-> doc
                               (assoc :xt/id eid
                                      :entity/id eid
                                      :entity/name name
                                      :entity/type :pattern/library
                                      :entity/external-id (or (:entity/external-id doc) (str name)))
                               (cond-> (nil? (:futon1a/backfill/orig-type doc))
                                 (assoc :futon1a/backfill/orig-type :devmap/prototype)))
                   component-id (stable-component-id eid)
                   component-name (str name "/01-summary")
                   component {:xt/id component-id
                              :entity/id component-id
                              :entity/name component-name
                              :entity/type :pattern/component
                              :entity/external-id (str name "::prototype-summary")
                              :entity/source (or (:entity/source doc) "futon3/devmap")}
                   includes-rel-id (stable-includes-rel-id eid component-id)
                   includes-rel {:xt/id includes-rel-id
                                 :relation/id includes-rel-id
                                 :relation/type :pattern/includes
                                 :relation/src eid
                                 :relation/dst component-id
                                 :relation/from eid
                                 :relation/to component-id
                                 :relation/provenance {:note ":pattern/includes"
                                                       :source "futon1a backfill prototypes"}}]
               [[:xtdb.api/put updated]
                [:xtdb.api/put component]
                [:xtdb.api/put includes-rel]]))
           protos)
          ;; Add :pattern/has-sigil relations mirroring :prototype/has-sigil.
          (mapcat
           (fn [[src-id dst-id]]
             (when (contains? by-id src-id)
               (let [rid (stable-sigil-rel-id src-id dst-id)
                     rdoc {:xt/id rid
                           :relation/id rid
                           :relation/type :pattern/has-sigil
                           :relation/src src-id
                           :relation/dst dst-id
                           :relation/from src-id
                           :relation/to dst-id
                           :relation/provenance {:note ":pattern/has-sigil"
                                                 :source "futon1a backfill prototypes"}}]
                 [[:xtdb.api/put rdoc]])))
           sigil-edges)))]
    (when (seq tx-ops)
      (pipeline/run-write! {:store store
                            :penholder penholder
                            :allowed-penholders (or allowed-penholders #{})
                            :model {:id 1}
                            :required-keys #{:id}
                            :identity nil
                            :tx-ops tx-ops
                            :claim {:op :backfill/prototypes-as-patterns}
                            :detail {:count (count protos)}}))
    {:ok? true
     :updated (count protos)
     :sigil-links (count sigil-edges)
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
          ;; For an explicit CLI migration, allow the caller penholder by default.
          (println (pr-str (backfill! {:node node
                                       :store store
                                       :penholder ph
                                       :allowed-penholders #{ph}}))))
        (finally
          (xtnode/stop-node! node))))))
