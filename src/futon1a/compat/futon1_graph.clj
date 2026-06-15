(ns futon1a.compat.futon1-graph
  "Compatibility queries for Futon1 graph-memory API shapes.

   These endpoints exist so Futon3 can treat Futon1a as a drop-in API base
   (Option 2: full replacement). They are read-only queries over XTDB.
  "
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon1a.model.descriptor-store :as dstore]
            [xtdb.api :as xtdb]))

(def ^:private sip-lexicon-path
  "/home/joe/code/futon6/data/mission-self-representing-lexicon.json")

(def ^:private structural-scope-roles #{:entity :environment :heading :parent :child :bounded-item :source})

(def ^:private sip-scores
  (delay
    (try
      (let [doc (json/parse-string (slurp (io/file sip-lexicon-path)) keyword)]
        (into {}
              (keep (fn [{:keys [term score]}]
                      (when term [(str term) (double (or score 0.0))])))
              (:terms doc)))
      (catch Exception _
        {}))))

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

(defn- hyperedge-endpoint-ids
  [h]
  (let [from-ends (->> (:hx/ends h)
                       (keep :entity-id)
                       (seq))]
    (vec (or from-ends (:hx/endpoints h) []))))

(defn- normalize-hyperedge-relation
  [h]
  (cond-> {:type (normalize-type (:hx/type h))}
    (some? (:hx/id h)) (assoc :hyperedge-id (:hx/id h))
    (some? (:hx/labels h)) (assoc :labels (:hx/labels h))))

(defn- hyperedges-by-endpoint
  [db endpoint-id]
  (when (seq (str endpoint-id))
    (->> (xtdb/q db '{:find [(pull e [*])]
                      :in [eid]
                      :where [[e :hx/endpoints eid]]}
                 endpoint-id)
         (map first)
         (sort-by #(str (:hx/id %)))
         (vec))))

(defn- inferred-endpoint-entity
  [endpoint-id]
  (let [s (str endpoint-id)]
    (cond
      (str/starts-with? s "dir:")
      nil

      (str/includes? s "-d/file/")
      {:id s :name s :type :file}

      (str/includes? s "-d/mission/")
      {:id s :name s :type :mission/doc}

      :else
      nil)))

(defn- endpoint-entity
  [node endpoint-id]
  (or (some-> (fetch-entity node endpoint-id)
              normalize-entity)
      (inferred-endpoint-entity endpoint-id)))

(defn- hyperedge-links-for-ego
  [node src-doc fallback-name]
  (let [db (xtdb/db node)
        src-id (or (:xt/id src-doc) (:entity/id src-doc))
        src-name (or (:entity/name src-doc) fallback-name)
        focus-ids (set (remove nil? [(some-> src-id str)
                                     (some-> src-name str)
                                     (some-> fallback-name str)]))
        query-ids (distinct (remove nil? [src-name src-id fallback-name]))
        hyperedges (->> query-ids
                        (mapcat #(hyperedges-by-endpoint db %))
                        (distinct)
                        (sort-by #(str (:hx/id %))))
        focus-endpoint? #(contains? focus-ids (str %))]
    (->> hyperedges
         (mapcat (fn [h]
                   (for [end-id (hyperedge-endpoint-ids h)
                         :when (not (focus-endpoint? end-id))
                         :let [end-entity (endpoint-entity node end-id)]
                         :when end-entity]
                     {:relation (normalize-hyperedge-relation h)
                      :entity end-entity})))
         (vec))))

(defn- type-string [t]
  (cond
    (keyword? t) (if-let [n (namespace t)] (str n "/" (name t)) (name t))
    (some? t) (str t)
    :else ""))

(defn- mission-scope-hyperedge? [h]
  (str/starts-with? (type-string (:hx/type h)) "mission-scope/"))

(defn- nesting-hyperedge? [h]
  (= "mission-scope/nesting" (type-string (:hx/type h))))

(defn- end-role [end]
  (normalize-type (:role end)))

(defn- scope-end-id [h]
  (or (get-in h [:hx/props :scope/id])
      (some (fn [{:keys [entity-id] :as end}]
              (when (contains? #{:environment :heading} (end-role end)) entity-id))
            (:hx/ends h))))

(defn- scope-title [node h sid]
  (or (get-in h [:hx/props :scope/name])
      (some-> (endpoint-entity node sid) :name)
      sid))

(defn- scope-binder [h]
  (or (get-in h [:hx/props :scope/binder-type])
      (some-> (type-string (:hx/type h)) (str/split #"/") last)
      "scope"))

(defn- endpoint-term [node end]
  (let [entity (endpoint-entity node (:entity-id end))]
    (or (:name entity) (:entity-id end))))

(defn- filler [node end]
  (let [role (end-role end)]
    (when-not (contains? structural-scope-roles role)
      (let [entity (endpoint-entity node (:entity-id end))
            term (endpoint-term node end)]
        (when (seq (str term))
          {:id (:entity-id end)
           :name term
           :type (type-string (or (:type entity) role))
           :role (type-string role)
           :sip (double (get @sip-scores term 0.0))})))))

(defn- top-sip [fillers k]
  (->> fillers
       (group-by :name)
       (map (fn [[term fs]] {:term term :score (apply max (map :sip fs))}))
       (sort-by (comp - :score))
       (take k)
       (vec)))

(defn- child-map [scope-hyperedges]
  (let [by-props (for [h scope-hyperedges
                       :let [sid (scope-end-id h)
                             parent (get-in h [:hx/props :scope/parent])]
                       :when (and sid parent)]
                   [parent sid])
        by-nesting (for [h scope-hyperedges
                         :when (nesting-hyperedge? h)
                         :let [parent (or (get-in h [:hx/props :scope/parent])
                                          (some (fn [e]
                                                  (when (= :parent (end-role e)) (:entity-id e)))
                                                (:hx/ends h)))
                               child (or (get-in h [:hx/props :scope/child])
                                         (some (fn [e]
                                                 (when (= :child (end-role e)) (:entity-id e)))
                                               (:hx/ends h)))]
                         :when (and parent child)]
                     [parent child])]
    (reduce (fn [m [p c]] (update m p (fnil conj #{}) c)) {} (concat by-props by-nesting))))

(defn- scope-fold-tree [node src-doc fallback-name]
  (let [db (xtdb/db node)
        src-id (or (:xt/id src-doc) (:entity/id src-doc))
        src-name (or (:entity/name src-doc) fallback-name)
        query-ids (distinct (remove nil? [src-id src-name fallback-name]))
        scope-hyperedges (->> query-ids
                              (mapcat #(hyperedges-by-endpoint db %))
                              (filter mission-scope-hyperedge?)
                              (distinct)
                              (sort-by #(str (:hx/id %)))
                              vec)
        content-hyperedges (remove nesting-hyperedge? scope-hyperedges)
        children (child-map scope-hyperedges)
        nodes (into {}
                    (for [h content-hyperedges
                          :let [sid (scope-end-id h)]
                          :when sid]
                      [sid {:scope-id sid
                            :binder (scope-binder h)
                            :title (scope-title node h sid)
                            :parent (get-in h [:hx/props :scope/parent])
                            :fillers (vec (keep #(filler node %) (:hx/ends h)))
                            :children (vec (sort (get children sid)))}]))
        child-ids (set (mapcat :children (vals nodes)))
        roots (->> (keys nodes)
                   (remove child-ids)
                   sort
                   vec)]
    (when (seq nodes)
      {:nodes nodes
       :roots roots
       :raw-scopes (count nodes)
       :raw-slots (reduce + (map (comp count :fillers) (vals nodes)))})))

(defn- aggregate-scope [nodes sid seen]
  (if (contains? @seen sid)
    {:sub-count 0 :sub-mass 0.0 :sub-fillers []}
    (do
      (swap! seen conj sid)
      (let [{:keys [fillers children]} (get nodes sid)
            child-aggregates (map #(aggregate-scope nodes % seen) children)
            sub-fillers (vec (concat fillers (mapcat :sub-fillers child-aggregates)))]
        {:sub-count (count sub-fillers)
         :sub-mass (double (reduce + (map :sip sub-fillers)))
         :sub-fillers sub-fillers}))))

(defn- visible-scope-ids [nodes aggregates roots depth]
  (letfn [(walk [sid d]
            (cons sid
                  (when (< d depth)
                    (->> (:children (get nodes sid))
                         (sort-by (comp - :sub-mass aggregates))
                         (mapcat #(walk % (inc d)))))))]
    (vec (mapcat #(walk % 0) roots))))

(defn- frame-entity [frame]
  {:id (:scope-id frame)
   :name (:title frame)
   :type :scope/frame
   :props {:scope/id (:scope-id frame)
           :scope/binder (:binder frame)
           :fold/sub-count (:sub-count frame)
           :fold/top-concepts (:top-concepts frame)
           :fold/fillers (:fillers frame)
           :fold/child-count (count (:children frame))}})

(defn folded-ego
  "Return a reduced mission scope-anatomy projection for dense mission egos."
  [node name {:keys [depth top-k] :or {depth 1 top-k 4}}]
  (let [src-doc (fetch-entity node name)
        base-entity (normalize-entity src-doc)]
    (when-let [{:keys [nodes roots raw-scopes raw-slots]} (scope-fold-tree node src-doc name)]
      (let [aggregates (into {} (for [sid (keys nodes)]
                                  [sid (aggregate-scope nodes sid (atom #{}))]))
            ordered-roots (sort-by (comp - :sub-mass aggregates) roots)
            visible-ids (visible-scope-ids nodes aggregates ordered-roots (long (or depth 1)))
            frames (mapv (fn [sid]
                           (let [node (get nodes sid)
                                 aggregate (get aggregates sid)
                                 fillers (:sub-fillers aggregate)]
                             (assoc node
                                    :sub-count (:sub-count aggregate)
                                    :sub-mass (:sub-mass aggregate)
                                    :top-concepts (top-sip fillers top-k)
                                    :fillers fillers)))
                         visible-ids)
            outgoing (mapv (fn [frame]
                             {:relation {:type :mission-scope/folded-frame}
                              :entity (frame-entity frame)})
                           frames)]
        {:entity base-entity
         :outgoing outgoing
         :incoming []
         :fold {:raw-count (+ raw-scopes raw-slots)
                :raw-scopes raw-scopes
                :raw-slots raw-slots
                :visible-count (count frames)
                :depth (long (or depth 1))
                :frames (mapv #(select-keys % [:scope-id :binder :title :sub-count :sub-mass
                                               :top-concepts :children :parent])
                              frames)}}))))

(defn ego
  "Return a Futon1-style ego view with outgoing and incoming links."
  [node name]
  (let [db (xtdb/db node)
        src-doc (fetch-entity node name)
        src-id (or (:xt/id src-doc) name)
        ;; Outgoing: this entity is the source
        q-src '{:find [(pull r [*])]
                :in [src]
                :where [[r :relation/type _]
                        [r :relation/src src]]}
        q-from '{:find [(pull r [*])]
                 :in [src]
                 :where [[r :relation/type _]
                         [r :relation/from src]]}
        ;; Incoming: this entity is the destination
        q-dst '{:find [(pull r [*])]
                :in [dst]
                :where [[r :relation/type _]
                        [r :relation/dst dst]]}
        q-to '{:find [(pull r [*])]
               :in [dst]
               :where [[r :relation/type _]
                        [r :relation/to dst]]}
        out-rels (->> (concat (map first (xtdb/q db q-src src-id))
                              (map first (xtdb/q db q-from src-id)))
                      (distinct)
                      (vec))
        in-rels (->> (concat (map first (xtdb/q db q-dst src-id))
                             (map first (xtdb/q db q-to src-id)))
                     (distinct)
                     (vec))
        outgoing (->> out-rels
                      (keep (fn [r]
                              (when-let [dst (relation-dst-id r)]
                                (when-let [dst-doc (xtdb/entity db dst)]
                                  {:relation (normalize-relation r)
                                   :entity (normalize-entity dst-doc)}))))
                      (vec))
        incoming (->> in-rels
                      (keep (fn [r]
                              (let [src (relation-src-id r)]
                                (when (and src (not= src src-id))
                                  (when-let [src-doc (xtdb/entity db src)]
                                     {:relation (normalize-relation r)
                                     :entity (normalize-entity src-doc)})))))
                      (vec))
        hx-outgoing (hyperedge-links-for-ego node src-doc name)]
    {:entity (normalize-entity src-doc)
     :outgoing (vec (concat outgoing hx-outgoing))
     :incoming incoming}))

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
