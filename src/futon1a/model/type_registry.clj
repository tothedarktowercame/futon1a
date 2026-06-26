(ns futon1a.model.type-registry
  "XTDB-backed type registry (Prototype 2).

   Tracks entity/relation/intent types as persistent documents.

   Pattern: storage/schema-evolution-stability
   Theory:  futon-theory/counter-ratchet"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [xtdb.api :as xtdb]))

(defn- type-id->xt-id
  "Match futon1's historical style: \"type|<kind>|:<ns>/<name>\"."
  [kind type-id]
  (str "type|" (name kind) "|" (if (keyword? type-id) (str type-id) (str type-id))))

(defn infer-parent
  "Infer parent from keyword namespace: :project/chapter -> :project."
  [type-id]
  (when (keyword? type-id)
    (when-let [ns (namespace type-id)]
      (keyword ns))))

(defn type-doc
  [{:keys [type-id kind parent aliases]}]
  (when-not (keyword? type-id)
    (throw (ex-info "type-id must be keyword" {:type-id type-id})))
  (when-not (contains? #{:entity :relation :intent} kind)
    (throw (ex-info "kind must be :entity/:relation/:intent" {:kind kind})))
  {:xt/id (type-id->xt-id kind type-id)
   :type/id type-id
   :type/kind kind
   :type/parent (or parent (infer-parent type-id))
   :type/aliases (vec (distinct (filter keyword? (or aliases []))))})

(defn types-from-doc
  "Extract type facts from a domain doc."
  [doc]
  (let [compat (:futon1a/compat doc)]
    (cond-> []
      (:entity/type doc) (conj {:kind :entity :type-id (:entity/type doc)})
      (:relation/type doc) (conj {:kind :relation :type-id (:relation/type doc)})
      (:intent/type doc) (conj {:kind :intent :type-id (:intent/type doc)})
      (and (= compat :entity) (:type doc)) (conj {:kind :entity :type-id (:type doc)})
      (and (= compat :relation) (:type doc)) (conj {:kind :relation :type-id (:type doc)}))))

(defn tx-ops-for-docs
  "Return idempotent XTDB tx-ops that ensure types exist in the registry.

   This intentionally always emits puts; the registry is canonicalized by xt/id."
  [docs]
  (let [types (->> docs
                   (filter map?)
                   (mapcat types-from-doc)
                   (filter (fn [{:keys [type-id]}] (keyword? type-id)))
                   (distinct))
        inferred-parents (->> types
                              (keep (fn [{:keys [type-id kind]}]
                                      (when-let [p (infer-parent type-id)]
                                        {:kind kind :type-id p})))
                              (distinct))
        all (distinct (concat types inferred-parents))]
    (mapv (fn [{:keys [type-id kind]}]
            [:xtdb.api/put (type-doc {:type-id type-id :kind kind})])
          all)))

(defn list-types
  "Return all type docs from XTDB node."
  [node]
  (let [db (xtdb/db node)]
    ;; Filter on :type/kind to avoid matching unrelated docs that carry a :type/id
    ;; key (e.g. model entity-type definitions).
    (->> (xtdb/q db '{:find [(pull e [*])]
                      :where [[e :type/kind _]]})
         (map first)
         (sort-by (fn [m]
                    (str (name (:type/kind m)) "|" (str (:type/id m)))))
         (vec))))

(defn- normalize-type-id [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (str/trim v)]
                  (when (seq s)
                    (keyword (if (str/starts-with? s ":") (subs s 1) s))))
    :else nil))

(defn merge-aliases-tx
  "Return a tx-op to merge aliases into an existing type doc (or create it)."
  [{:keys [node kind type-id aliases]}]
  (let [type-id (normalize-type-id type-id)
        aliases (->> aliases (map normalize-type-id) (remove nil?) distinct vec)
        xt-id (type-id->xt-id kind type-id)
        db (xtdb/db node)
        existing (xtdb/entity db xt-id)
        merged (type-doc {:type-id type-id
                          :kind kind
                          :parent (:type/parent existing)
                          :aliases (vec (distinct (concat (:type/aliases existing) aliases)))})]
    [:xtdb.api/put merged]))

(defn type-xt-ids-present
  "All xt/ids under which `type-id` is CURRENTLY registered — across kinds
   (entity/relation/intent) and both historical encodings (colon `:ns/name`
   and the no-colon relic `ns/name`). Used by erasure (`pipeline/retract-type!`)
   so retraction removes every variant of a noise type-doc, not just the
   canonical one. Read-only; returns only the xt/ids that actually exist."
  [node type-id]
  (let [db  (xtdb/db node)
        tid (normalize-type-id type-id)
        nm  (when tid (let [s (str tid)] (if (str/starts-with? s ":") (subs s 1) s)))]
    (when nm
      (->> (for [kind ["entity" "relation" "intent"]
                 enc  [(str ":" nm) nm]]
             (str "type|" kind "|" enc))
           distinct
           (filter #(some? (xtdb/entity db %)))
           vec))))

(defn set-parent-tx
  "Return a tx-op to set a type's parent (override inference)."
  [{:keys [node kind type-id parent]}]
  (let [type-id (normalize-type-id type-id)
        parent (normalize-type-id parent)
        xt-id (type-id->xt-id kind type-id)
        db (xtdb/db node)
        existing (xtdb/entity db xt-id)
        doc (type-doc {:type-id type-id
                       :kind kind
                       :parent parent
                       :aliases (:type/aliases existing)})]
    [:xtdb.api/put doc]))
