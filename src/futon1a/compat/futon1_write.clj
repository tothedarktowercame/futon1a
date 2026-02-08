(ns futon1a.compat.futon1-write
  "Compatibility write semantics for Futon1 API shapes.

   These are used by futon3's existing sync scripts, which expect Futon1's
   ensure/upsert behavior (id stable by name).
  "
  (:require [clojure.string :as str]
            [xtdb.api :as xtdb])
  (:import (java.util UUID)))

(defn- normalize-type
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (str/trim v)
                      s (if (str/starts-with? s ":") (subs s 1) s)]
                  (when (seq s) (keyword s)))
    :else nil))

(defn- normalize-text [v]
  (let [s (when (string? v) (str/trim v))]
    (when (seq s) s)))

(defn- uuid-str []
  (str (UUID/randomUUID)))

(defn- uuid-string?
  [s]
  (boolean
    (when (string? s)
      (try (UUID/fromString s) true
           (catch Exception _ false)))))

(defn- entity-by-name
  "Return a canonical entity doc matching :entity/name.

   Historically we required exactly one match; that made repeated sync runs
   generate duplicates (because \"multiple\" was treated the same as \"none\").
   For Futon1 compatibility we must be stable-by-name, even when legacy data
   already contains duplicates.

   Canonical selection rule:
   - prefer docs whose :entity/type matches `wanted-type` when provided
   - otherwise choose the lexicographically-smallest :entity/id (stable)."
  ([node name] (entity-by-name node name nil))
  ([node name wanted-type]
   (let [db (xtdb/db node)
         wanted (normalize-type wanted-type)
         docs (->> (xtdb/q db '{:find [(pull e [:entity/id :entity/name :entity/type :entity/external-id :entity/source])]
                                :in [name]
                                :where [[e :entity/name name]]}
                             name)
                   (map first)
                   (vec))
         candidates (cond-> docs
                      wanted (->> (filter #(= wanted (:entity/type %))) vec))
         chosen (->> (or (not-empty candidates) docs)
                     (sort-by (fn [d] (str (:entity/id d))))
                     first)]
     chosen)))

(defn ensure-entity-doc
  "Build an idempotent Futon1-style entity doc from a request payload.

   Inputs:
   - node
   - payload: {:name, :type, :external-id, :source, :id?}

   Returns {:doc <xtdb-doc> :entity <public-entity>}."
  [{:keys [node payload]}]
  (let [name (normalize-text (:name payload))
        type (normalize-type (:type payload))
        external-id (normalize-text (:external-id payload))
        source (normalize-text (:source payload))
        requested-id (normalize-text (:id payload))
        existing (when name (entity-by-name node name type))
        entity-id (or (some-> existing :entity/id str)
                      requested-id
                      (uuid-str))
        doc (cond-> {:xt/id entity-id
                     :entity/id entity-id
                     :entity/name name}
              type (assoc :entity/type type)
              external-id (assoc :entity/external-id external-id)
              source (assoc :entity/source source))
        public {:id entity-id :name name :type type
                :external-id external-id :source source}]
    {:doc doc
     :entity (-> public
                 (update :external-id (fn [v] (when (seq (str v)) v)))
                 (update :source (fn [v] (when (seq (str v)) v)) ))}))

(defn- relation-endpoint-id
  [node v]
  (cond
    (string? v) (let [s (normalize-text v)]
                  (when s
                    (or (some-> (entity-by-name node s) :entity/id str)
                        (when (uuid-string? s) s))))
    (map? v) (or (normalize-text (:id v))
                 (normalize-text (:entity/id v))
                 (when-let [n (normalize-text (:name v))]
                   (some-> (entity-by-name node n) :entity/id str)))
    :else nil))

(defn- relation-keyword-from-note
  [note]
  (when-let [s (normalize-text note)]
    (let [s (if (str/starts-with? s ":") (subs s 1) s)]
      (when (seq s) (keyword s)))))

(defn- stable-relation-id
  [{:keys [src-id rel-type dst-id note order]}]
  ;; Futon1 uses UUIDs, but for determinism and idempotence we use a stable string id.
  ;; This avoids duplicate relation docs on repeated sync runs.
  (str "rel|"
       src-id "|"
       (if (keyword? rel-type) (subs (str rel-type) 1) (str rel-type)) "|"
       dst-id "|"
       (or note "") "|"
       (or order "")))

(defn upsert-relation-doc
  "Build a Futon1-style relation doc from a request payload.

   Inputs:
   - node
   - payload: {:type, :src, :dst, :provenance, :id?}

   Returns {:doc <xtdb-doc> :relation <public-relation>}."
  [{:keys [node payload]}]
  (let [rel-type (normalize-type (:type payload))
        prov (:provenance payload)
        note (when (map? prov) (normalize-text (:note prov)))
        order (when (map? prov) (:order prov))
        src-id (relation-endpoint-id node (:src payload))
        dst-id (relation-endpoint-id node (:dst payload))
        requested-id (normalize-text (:id payload))
        _ (when-not (and src-id dst-id rel-type)
            (throw (ex-info "relation requires resolvable src/dst and type"
                            {:src (:src payload)
                             :dst (:dst payload)
                             :type (:type payload)})))
        rel-id (or requested-id
                   (stable-relation-id {:src-id src-id :rel-type rel-type :dst-id dst-id
                                        :note note :order order}))
        doc (cond-> {:xt/id rel-id
                     :relation/id rel-id
                     :relation/type rel-type
                     ;; store both key variants so both futon1-style and futon1a-style readers work
                     :relation/src src-id
                     :relation/dst dst-id
                     :relation/from src-id
                     :relation/to dst-id}
              (map? prov) (assoc :relation/provenance prov))
        public {:id rel-id
                :type (or (relation-keyword-from-note note) rel-type)
                :relation/type rel-type
                :src-id src-id
                :dst-id dst-id
                :provenance prov}]
    {:doc doc
     :relation public}))
