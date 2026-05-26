(ns futon1a.model.verify
  "Descriptor verification (Prototype 2).

   Runs descriptor-declared invariants against live XTDB data.

   For Prototype 2 we require at least one working invariant check."
  (:require [futon1a.model.descriptor-store :as ds]
            [clojure.string :as str]
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

(defn invariant-patterns-language-has-source
  "Verify every :pattern/language entity has a non-empty :entity/source.

   Note: futon1 had a richer interpretation (links to a language-source tag),
   but for Prototype 2 we enforce the minimal, objective form."
  [node]
  (let [db (xtdb/db node)
        docs (->> (xtdb/q db '{:find [(pull e [:xt/id :entity/id :entity/name :entity/source])]
                               :where [[e :entity/type :pattern/language]]})
                  (map first))
        missing (->> docs
                     (filter (fn [d]
                               (let [s (:entity/source d)]
                                 (or (nil? s)
                                     (and (string? s) (empty? (str/trim s)))))))
                     (map (fn [d] {:entity/id (or (:entity/id d) (:xt/id d))
                                   :entity/name (:entity/name d)}))
                     (vec))]
    (if (empty? missing)
      (ok :patterns/language-has-source)
      (fail :patterns/language-has-source :missing-source {:missing missing}))))

(def ^:private flexiarg-central-five
  "The central five canonical clauses every flexiarg-shaped pattern is
   expected to bind via typed has-* relations. Mirrors the rulebook at
   futon3/holes/excursions/E-clause-vocabulary-reshape.sexp."
  [:context :if :however :then :because])

(defn- pattern-library-entities [db]
  (->> (xtdb/q db '{:find [(pull e [:xt/id :entity/id :entity/name :entity/type])]
                    :where [[e :entity/type :pattern/library]]})
       (map first)))

(defn- has-clause-relation? [db pattern-eid facet]
  (let [rel-type (keyword "pattern" (str "has-" (name facet)))]
    (some?
     (first
      (xtdb/q db
              '{:find [r]
                :in [src rt]
                :where [[r :relation/type rt]
                        [r :relation/from src]]}
              pattern-eid rel-type)))))

(defn- clause-entities-for-pattern [db pattern-eid]
  (->> (xtdb/q db
               '{:find [(pull dst [:xt/id :entity/id :entity/name :entity/type :entity/source])]
                 :in [src]
                 :where [[r :relation/from src]
                         [r :relation/type rt]
                         [(clojure.string/starts-with? (str rt) ":pattern/has-")]
                         [r :relation/to dst-id]
                         [dst :entity/id dst-id]]}
               pattern-eid)
       (map first)))

(defn invariant-pattern-has-required-clauses
  "Verify every :pattern/library entity has all five central canonical
   clauses bound via typed :pattern/has-{context,if,however,then,because}
   relations. Patterns missing any of the five are reported."
  [node]
  (let [db (xtdb/db node)
        patterns (pattern-library-entities db)
        missing (->> patterns
                     (keep (fn [p]
                             (let [eid (or (:entity/id p) (:xt/id p))
                                   absent (->> flexiarg-central-five
                                               (remove #(has-clause-relation? db eid %))
                                               vec)]
                               (when (seq absent)
                                 {:entity/id eid
                                  :entity/name (:entity/name p)
                                  :missing-facets absent}))))
                     (vec))]
    (if (empty? missing)
      (ok :patterns/has-required-clauses)
      (fail :patterns/has-required-clauses :missing-facets
            {:missing missing
             :total-patterns (count patterns)
             :missing-count (count missing)}))))

(defn invariant-pattern-has-conclusion
  "Verify every :pattern/library entity has a :pattern/has-conclusion
   relation. The conclusion is the only *required* canonical clause —
   the central five (context/if/however/then/because) are recommended
   but a definitional pattern legitimately may lack the operational
   four. The conclusion clause may have been authored under any of
   `! conclusion:`, `! claim:`, `! summary:`, or `! instantiated-by:`
   in the source; the canonical parser normalises all four to the
   same `:pattern/has-conclusion` edge."
  [node]
  (let [db (xtdb/db node)
        patterns (pattern-library-entities db)
        missing (->> patterns
                     (keep (fn [p]
                             (let [eid (or (:entity/id p) (:xt/id p))]
                               (when-not (has-clause-relation? db eid :conclusion)
                                 {:entity/id eid
                                  :entity/name (:entity/name p)}))))
                     (vec))]
    (if (empty? missing)
      (ok :patterns/has-conclusion)
      (fail :patterns/has-conclusion :missing-conclusion
            {:missing missing
             :total-patterns (count patterns)
             :missing-count (count missing)}))))

(defn invariant-clause-text-not-empty
  "Verify every :pattern/clause entity has non-empty :entity/source
   (the clause body text)."
  [node]
  (let [db (xtdb/db node)
        clauses (->> (xtdb/q db '{:find [(pull e [:xt/id :entity/id :entity/name :entity/source])]
                                  :where [[e :entity/type :pattern/clause]]})
                     (map first))
        empty (->> clauses
                   (filter (fn [c]
                             (let [s (:entity/source c)]
                               (or (nil? s)
                                   (and (string? s) (empty? (str/trim s)))))))
                   (map (fn [c] {:entity/id (or (:entity/id c) (:xt/id c))
                                 :entity/name (:entity/name c)}))
                   (vec))]
    (if (empty? empty)
      (ok :patterns/clause-text-not-empty)
      (fail :patterns/clause-text-not-empty :empty-clause-text {:empty empty}))))

(def ^:private invariant-runners
  {:meta/descriptor-has-certificate invariant-descriptor-has-certificate
   :patterns/language-has-source invariant-patterns-language-has-source
   :patterns/has-required-clauses invariant-pattern-has-required-clauses
   :patterns/has-conclusion invariant-pattern-has-conclusion
   :patterns/clause-text-not-empty invariant-clause-text-not-empty})

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
