(ns futon1a.model.verify
  "Descriptor verification (Prototype 2).

   Runs descriptor-declared invariants against live XTDB data.

   For Prototype 2 we require at least one working invariant check."
  (:require [futon1a.model.descriptor-store :as ds]
            [xtdb.api :as xtdb]))

(defn- ok [id] {:invariant id :ok? true})
(defn- fail [id reason context] {:invariant id :ok? false :reason reason :context context})

(defn invariant-descriptor-has-certificate
  "Verify all :model/descriptor entities have :schema/certificate with a penholder."
  [node]
  (let [db (xtdb/db node)
        docs (->> (xtdb/q db '{:find [(pull e [*])]
                               :where [[e :entity/type :model/descriptor]]})
                  (map first))
        missing (->> docs
                     (filter (fn [d] (not (get-in d [:schema/certificate :penholder]))))
                     (map (fn [d] {:model/scope (:model/scope d)
                                   :xt/id (:xt/id d)}))
                     (vec))]
    (if (empty? missing)
      (ok :meta/descriptor-has-certificate)
      (fail :meta/descriptor-has-certificate :missing-certificate {:missing missing}))))

(def ^:private invariant-runners
  {:meta/descriptor-has-certificate invariant-descriptor-has-certificate})

(defn verify-scope
  "Run invariants declared on a descriptor by scope.

   Returns {:ok? bool :scope kw :results [...]}"
  [node scope]
  (let [descriptor (ds/get-descriptor node scope)]
    (when-not descriptor
      (throw (ex-info "descriptor not found" {:scope scope})))
    (let [scope (:model/scope descriptor)
          invs (or (:invariants descriptor) [])
          results (mapv (fn [inv]
                          (if-let [runner (get invariant-runners inv)]
                            (runner node)
                            (fail inv :unknown-invariant {:known (vec (keys invariant-runners))})))
                        invs)]
      {:ok? (every? :ok? results)
       :scope scope
       :results results})))

