(ns futon1a.scripts.repair-legacy-descriptors
  "Repair/migrate legacy :model/descriptor docs in an existing store.

   Why: the migrated futon1 store can contain :model/descriptor entities that
   predate the Prototype 2 descriptor shape. Prototype 2's verify endpoint
   expects descriptors to carry a certificate; this script upgrades legacy docs
   in-place (same xt/id) to include the required fields.

   This is an explicit repair step (T7) and should not be silently done at read
   time."
  (:require [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [xtdb.api :as xtdb]))

(defn- ->millis
  [v]
  (cond
    (nil? v) nil
    (number? v) (long v)
    (instance? java.util.Date v) (.getTime ^java.util.Date v)
    (instance? java.time.Instant v) (.toEpochMilli ^java.time.Instant v)
    :else nil))

(defn- infer-scope-from-entity-name
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (when (seq s)
        (let [parts (str/split s #"/")]
          (when (<= 2 (count parts))
            (let [[a b c] parts]
              (when (and (= a "model") (= b "descriptor") (seq c))
                (keyword c)))))))))

(defn- ensure-cert
  [doc]
  (let [existing (:schema/certificate doc)
        penholder (or (get existing :penholder)
                      (get doc :penholder)
                      (get doc :model/penholder)
                      "futon1")
        issued-at (or (get existing :issued-at)
                      (->millis (:entity/updated-at doc))
                      (->millis (:entity/created-at doc))
                      0)]
    (assoc doc :schema/certificate {:penholder penholder
                                    :issued-at issued-at})))

(defn- normalize-legacy-descriptor
  "Return upgraded descriptor doc, preserving legacy fields."
  [doc]
  (-> doc
      (assoc :legacy/descriptor? true)
      (assoc :model/scope (or (:model/scope doc)
                              (infer-scope-from-entity-name (:entity/name doc))
                              (infer-scope-from-entity-name (:entity/lower-label doc))))
      (assoc :schema/version (or (:schema/version doc)
                                 (:entity/external-id doc)
                                 "0.0.0-legacy"))
      (ensure-cert)
      (assoc :entities (or (:entities doc) {}))
      (assoc :operations (or (:operations doc) {}))
      (assoc :stores (or (:stores doc) {:canonical :xtdb}))
      (assoc :invariants (or (:invariants doc) []))))

(defn legacy-descriptor-docs
  "Return all :model/descriptor docs that are missing a certificate penholder."
  [node]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [(pull e [*])]
                      :where [[e :entity/type :model/descriptor]]})
         (map first)
         (filter (fn [d] (not (get-in d [:schema/certificate :penholder]))))
         (vec))))

(defn repair!
  "Upgrade legacy :model/descriptor docs in-place.

   Inputs:
   - node (XTDB node) for querying
   - store (DurableStore) for writes (pipeline)
   - penholder (string)
   - allowed-penholders (set)
   - dry-run? (boolean, optional)

   Returns {:ok? bool :repaired N :tx-id ... :sample [...]}"
  [{:keys [node store penholder allowed-penholders dry-run?]}]
  (let [legacy (legacy-descriptor-docs node)
        patched (mapv normalize-legacy-descriptor legacy)
        tx-ops (mapv (fn [d] [::xtdb/put d]) patched)]
    (if dry-run?
      {:ok? true
       :dry-run? true
       :repaired (count patched)
       :sample (vec (take 3 (map (fn [d] (select-keys d [:xt/id :entity/name :model/scope :schema/version :schema/certificate]))
                                 patched)))}
      (if (empty? tx-ops)
        {:ok? true :repaired 0 :tx-id "tx-noop"}
        (let [resp (pipeline/run-write! {:store store
                                         :penholder penholder
                                         :allowed-penholders allowed-penholders
                                         :model {}
                                         :required-keys #{}
                                         :identity nil
                                         :tx-ops tx-ops
                                         :claim {:op :repair/legacy-descriptors}
                                         :detail {:layer :repair
                                                  :count (count patched)}})]
          {:ok? true
           :repaired (count patched)
           :tx-id (:tx-id resp)
           :path/id (get-in resp [:path :path/id])})))))

