(ns futon1a.core.invariants
  "Cross-layer invariant helpers (counter-ratchet scaffold).

   Invariant: I2 (Integrity — startup succeeds completely or fails loudly).
   Pattern:   storage/invariants-vs-repair
   Theory:    futon-theory/counter-ratchet"
  (:require [futon1a.core.xtdb :as xt]))

(defn layer2-error
  [reason context]
  {:error/layer 2
   :error/status 500
   :error/reason reason
   :error/context context})

(defn counter-ratchet
  "Ensure that a numeric count does not drop below its previous value.

   Inputs:
   - prev (number)
   - next (number)
   - label (keyword or string)

   Returns {:ok? true} or throws.
  "
  [{:keys [prev next label]}]
  (when-not (and (number? prev) (number? next))
    (throw (ex-info "counter-ratchet requires numeric prev/next"
                    {:error (layer2-error :counter-ratchet-invalid
                                          {:label label
                                           :prev prev
                                           :next next})})))
  (when (< next prev)
    (throw (ex-info "counter-ratchet violation"
                    {:error (layer2-error :counter-ratchet
                                          {:label label
                                           :prev prev
                                           :next next})})))
  {:ok? true})

(def ^:private protected-write-classes
  [:entity :relation :descriptor :docbook])

(defn- op-tag
  [op]
  (when (and (vector? op) (keyword? (first op)))
    (let [k (first op)]
      (keyword (or (namespace k) "") (name k)))))

(defn- op-id
  [op]
  (let [tag (op-tag op)]
    (cond
      (= tag :xtdb.api/put)
      (let [doc (second op)]
        (when (map? doc) (:xt/id doc)))

      (= tag :xtdb.api/delete)
      (second op)

      :else nil)))

(defn- doc->classes
  [doc]
  (when (map? doc)
    (cond-> #{}
      (= :model/descriptor (:entity/type doc)) (conj :descriptor)
      (:relation/id doc) (conj :relation)
      (:doc/id doc) (conj :docbook)
      (and (:entity/id doc)
           (not= :model/descriptor (:entity/type doc))) (conj :entity))))

(defn- count-by-class
  [docs]
  (reduce (fn [acc doc]
            (reduce (fn [m cls] (update m cls (fnil inc 0)))
                    acc
                    (or (doc->classes doc) #{})))
          {:entity 0 :relation 0 :descriptor 0 :docbook 0}
          docs))

(defn- apply-op
  [state op]
  (let [tag (op-tag op)]
    (cond
      (= tag :xtdb.api/put)
      (let [doc (second op)
            id (:xt/id doc)]
        (if (and (map? doc) id)
          (assoc state id doc)
          state))

      (= tag :xtdb.api/delete)
      (let [id (second op)]
        (if id
          (assoc state id nil)
          state))

      :else state)))

(defn enforce-counter-ratchet!
  "Always-on protected-class counter-ratchet guard for write tx plans.

   Inputs:
   - store (DurableStore, read via xt/entity)
   - tx-ops (vector)
   - allow-drop-classes (set of class keywords, optional)

   Protected classes:
   - :entity, :relation, :descriptor, :docbook

   Returns {:ok? true :counts {:before ... :after ...}} or throws."
  [{:keys [store tx-ops allow-drop-classes]}]
  (let [allow-drop-classes (set (or allow-drop-classes #{}))
        ids (->> tx-ops
                 (keep op-id)
                 distinct
                 vec)
        before-by-id (into {}
                           (map (fn [id] [id (xt/entity store id)]))
                           ids)
        after-by-id (reduce apply-op before-by-id tx-ops)
        before-counts (count-by-class (vals before-by-id))
        after-counts (count-by-class (vals after-by-id))]
    (doseq [label protected-write-classes
            :when (not (contains? allow-drop-classes label))]
      (counter-ratchet {:label label
                        :prev (get before-counts label 0)
                        :next (get after-counts label 0)}))
    {:ok? true
     :counts {:before before-counts
              :after after-counts
              :allow-drop-classes (vec (sort-by str allow-drop-classes))}}))
