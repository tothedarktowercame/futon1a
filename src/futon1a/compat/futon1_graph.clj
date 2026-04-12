(ns futon1a.compat.futon1-graph
  "Compatibility queries for Futon1 graph-memory API shapes.

   These endpoints exist so Futon3 can treat Futon1a as a drop-in API base
   (Option 2: full replacement). They are read-only queries over XTDB.
  "
  (:require [clojure.string :as str]
            [futon1a.model.descriptor-store :as dstore]
            [xtdb.api :as xtdb]))

(defn- normalize-type
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (str/trim v)
                      s (if (str/starts-with? s ":") (subs s 1) s)]
                  (when (seq s) (keyword s)))
    :else nil))

(defn normalize-entity
  "Normalize an XTDB entity doc to Futon1 API shape keys."
  [doc]
  (when (map? doc)
    (let [id (or (:entity/id doc) (:id doc) (:xt/id doc))
          name (or (:entity/name doc) (:name doc))
          type (or (normalize-type (:entity/type doc))
                   (normalize-type (:type doc)))
          external-id (or (:entity/external-id doc) (:external-id doc))
          source (or (:entity/source doc) (:source doc))
          sha (or (:media/sha256 doc) (:entity/media.sha256 doc))]
      (cond-> {:id id
               :name name
               :type type}
        (some? external-id) (assoc :external-id external-id)
        (some? source) (assoc :source source)
        (some? (:entity/props doc)) (assoc :props (:entity/props doc))
        (some? sha) (assoc :media/sha256 sha)
        ;; include raw for debugging when values are missing
        (or (nil? id) (nil? name) (nil? type)) (assoc :_raw (select-keys doc
                                                                        [:xt/id :id :name :type
                                                                         :entity/id :entity/name :entity/type
                                                                         :entity/external-id :entity/source]))))))

(defn fetch-entity
  "Fetch entity by XTDB id, falling back to lookup by :entity/name and then
  :entity/external-id when missing.

  Rationale: callers in the Futon1 ecosystem often address entities by name or
  external-id (not only by :xt/id)."
  [node id-or-name]
  (let [db (xtdb/db node)
        direct (when (seq (str id-or-name)) (xtdb/entity db id-or-name))]
    (or direct
        (when (seq (str id-or-name))
          ;; If duplicates exist, choose a deterministic canonical entity.
          ;; (See futon1a.compat.futon1-write/entity-by-name for rationale.)
          (let [matches (->> (xtdb/q db '{:find [(pull e [:entity/id])]
                                          :in [name]
                                          :where [[e :entity/name name]]}
                                       id-or-name)
                             (map first)
                             (map (fn [m] (or (:entity/id m) (:xt/id m))))
                             (keep identity)
                             (map str)
                             (sort)
                             (vec))]
            (when-let [eid (first matches)]
              (xtdb/entity db eid))))
        (when (seq (str id-or-name))
          ;; External-id fallback: used by Arxana lyrics ids (string external ids).
          (let [matches (->> (xtdb/q db '{:find [(pull e [:xt/id :entity/id])]
                                          :in [external]
                                          :where [[e :entity/external-id external]]}
                                       id-or-name)
                             (map first)
                             (map (fn [m] (or (:entity/id m) (:xt/id m))))
                             (keep identity)
                             (map str)
                             (sort)
                             (vec))]
            (when-let [eid (first matches)]
              (xtdb/entity db eid)))))))

(defn entities-latest
  "Return entities by type (keyword) up to limit."
  [node {:keys [type limit]}]
  (let [db (xtdb/db node)
        t (normalize-type type)
        n (long (max 1 (or limit 1)))
        q (if (= t :pattern/library)
            ;; Futon3's \"standard library\" enumerator is patterns-index.tsv, which
            ;; corresponds to patterns with an explicit :pattern/has-sigil link.
            ;; Filter here so /entities/latest matches futon3's expectations even
            ;; when the store contains additional unsigiled pattern/library docs.
            '{:find [(pull e [*])]
              :in [t]
              :where [[e :entity/type t]
                      [r :relation/type :pattern/has-sigil]
                      [r :relation/src e]
                      [r :relation/dst s]
                      [s :entity/type :pattern/sigil]]}
            '{:find [(pull e [*])]
              :in [t]
              :where [[e :entity/type t]]})
        ;; De-dupe by entity/name to avoid legacy duplicate-by-name docs breaking
        ;; callers that address entities by name.
        docs (->> (xtdb/q db q t)
                  (map first)
                  (group-by (fn [d] (or (:entity/name d) (:name d))))
                  (map (fn [[_name docs]]
                         (->> docs
                              (sort-by (fn [d] (str (or (:entity/id d) (:xt/id d) ""))))
                              first)))
                  (sort-by (fn [d] (or (:entity/name d) (:name d) "")))
                  (take n)
                  (mapv normalize-entity))]
    {:type t
     :entities docs}))

(defn- relation-src-id [doc]
  (or (:relation/src doc) (:relation/from doc)))

(defn- relation-dst-id [doc]
  (or (:relation/dst doc) (:relation/to doc)))

(defn- normalize-relation
  [doc]
  (when (map? doc)
    (let [t (normalize-type (:relation/type doc))
          prov (:relation/provenance doc)
          note (when (map? prov) (:note prov))
          ;; Futon1 scholium relations encode the domain relation as provenance note.
          derived (when (and note (or (= t :arxana/scholium) (= (str t) ":arxana/scholium")))
                    (normalize-type note))
          label (or note (:label doc))]
      (cond-> {:type (or derived t)}
        (some? label) (assoc :label label)
        (some? prov) (assoc :provenance prov)))))

(defn ego
  "Return a Futon1-style ego view with outgoing links."
  [node name]
  (let [db (xtdb/db node)
        src-doc (fetch-entity node name)
        src-id (or (:xt/id src-doc) name)
        q-src '{:find [(pull r [*])]
                :in [src]
                :where [[r :relation/type _]
                        [r :relation/src src]]}
        q-from '{:find [(pull r [*])]
                 :in [src]
                 :where [[r :relation/type _]
                         [r :relation/from src]]}
        rels (->> (concat (map first (xtdb/q db q-src src-id))
                          (map first (xtdb/q db q-from src-id)))
                  (distinct)
                  (vec))
        outgoing (->> rels
                      (keep (fn [r]
                              (when-let [dst (relation-dst-id r)]
                                (when-let [dst-doc (xtdb/entity db dst)]
                                  {:relation (normalize-relation r)
                                   :entity (normalize-entity dst-doc)}))))
                      (vec))]
    {:entity (normalize-entity src-doc)
     :outgoing outgoing}))

(defn patterns-registry
  "Return a lightweight registry of pattern entities and relations.

   This uses the stored :patterns model descriptor as the source of truth for
   which entity types are considered part of the pattern registry."
  [node]
  (let [db (xtdb/db node)
        desc (dstore/get-descriptor node :patterns)
        types (set (keys (or (:entities desc) {})))
        entities (if (seq types)
                   (->> (xtdb/q db '{:find [(pull e [:entity/id :entity/name :entity/type :entity/external-id])]
                                     :in [types]
                                     :where [[e :entity/type t]
                                             [(contains? types t)]]}
                                  types)
                        (map first)
                        (mapv (fn [doc]
                                {:id (:entity/id doc)
                                 :name (:entity/name doc)
                                 :type (some-> (:entity/type doc) str (str/replace #"^:" ""))
                                 :external-id (:entity/external-id doc)})))
                   [])
        relations (if (seq types)
                    (->> (xtdb/q db '{:find [(pull r [:relation/type :relation/provenance :relation/src :relation/dst])]
                                      :in [types]
                                      :where [[r :relation/type _]
                                              [r :relation/src src]
                                              [r :relation/dst dst]
                                              [src :entity/type st]
                                              [dst :entity/type dt]
                                              [(contains? types st)]
                                              [(contains? types dt)]]}
                                   types)
                         (map first)
                         (mapv (fn [doc]
                                 (let [prov (:relation/provenance doc)
                                       note (when (map? prov) (:note prov))]
                                   {:type (some-> (:relation/type doc) str (str/replace #"^:" ""))
                                    :label note
                                    :provenance prov
                                    :src-id (:relation/src doc)
                                    :dst-id (:relation/dst doc)}))))
                    [])]
    {:generated-at (System/currentTimeMillis)
     :source "xtdb"
     :entities entities
     :relations relations
     :counts {:entities (count entities)
              :relations (count relations)}}))
