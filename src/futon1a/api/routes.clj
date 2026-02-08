(ns futon1a.api.routes
  "Minimal API surface for futon1a.

   Pattern:  storage/canonical-interface
   Theory:   futon-theory/error-hierarchy (all handlers use with-error-handling)"
  (:require [clojure.string :as str]
            [futon1a.api.errors :as errors]
            [futon1a.compat.futon1-graph :as f1g]
            [futon1a.compat.futon1-model :as f1m]
            [futon1a.compat.futon1-write :as f1w]
            [futon1a.diag.health :as health]
            [futon1a.core.identity :as id]
            [futon1a.model.descriptor-store :as dstore]
            [futon1a.model.registry :as registry]
            [futon1a.model.type-registry :as types]
            [futon1a.model.validation :as mv]
            [futon1a.model.verify :as verify]
            [futon1a.scripts.repair :as repair]
            [futon1a.core.pipeline :as pipeline]
            [xtdb.api :as xtdb]))

(defn ok
  [body]
  {:status 200
   :body body})

(defn- require-keys!
  [m ks]
  (doseq [k ks]
    (when-not (contains? m k)
      (throw (ex-info "missing required key"
                      {:error (mv/layer4-error :missing-required
                                               {:missing k
                                                :required ks})}))))
  m)

(defn- handle-error
  [e]
  (errors/error->response (ex-data e)))

(defn- handle-unexpected
  [e]
  {:status 500
   :body {:error {:reason :exception
                  :message (.getMessage e)}}})

(defmacro ^:private with-error-handling
  [& body]
  `(try
     ~@body
     (catch clojure.lang.ExceptionInfo e#
       (handle-error e#))
     (catch Exception e#
       (handle-unexpected e#))))

(defn health
  "Health endpoint. Accepts optional checks/counts metadata."
  ([] (health {}))
  ([opts]
   (let [report (health/health-report opts)]
     {:status (if (= :ok (:status report)) 200 503)
      :body report})))

(defn write
  "Write handler.

   Expects a map with keys:
   - store
   - penholder
   - allowed-penholders
   - model
   - required-keys
   - identity
   - tx-ops
  "
  [req]
  (with-error-handling
    (let [result (pipeline/run-write! req)]
      (ok {:tx-id (:tx-id result)
           :path/id (get-in result [:path :path/id])}))))

(defn register-model
  "Register a model descriptor.

   Expects:
   - id (keyword)
   - descriptor (map)"
  [req]
  (with-error-handling
    (require-keys! req #{:id :descriptor})
    (ok (registry/register-model! req))))

(defn list-models
  "List all registered model ids."
  []
  (ok {:models (vec (registry/list-models))}))

(defn meta-model
  "Fetch a model descriptor by scope from XTDB.

   Expects:
   - node
   - scope (keyword or string)"
  [{:keys [node scope]}]
  (with-error-handling
    (require-keys! {:node node :scope scope} #{:node :scope})
    (if-let [doc (dstore/get-descriptor node scope)]
      (ok doc)
      {:status 404
       :body {:error {:reason :not-found
                      :scope scope}}})))

(defn meta-models
  "List all model descriptors from XTDB.

   Expects:
   - node"
  [{:keys [node]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (ok {:descriptors (dstore/list-descriptors node)})))

(defn meta-model-verify
  "Verify a descriptor's invariants against live XTDB data.

   Expects:
   - node
   - scope"
  [{:keys [node scope]}]
  (with-error-handling
    (require-keys! {:node node :scope scope} #{:node :scope})
    (ok (verify/verify-scope node scope))))

(defn list-types
  "List all registered type docs from XTDB.

   Expects:
   - node"
  [{:keys [node]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (ok {:types (types/list-types node)})))

(defn types-parent
  "Override a type's parent.

   Expects:
   - node
   - store
   - penholder
   - allowed-penholders
   - type/id (keyword or string)
   - type/kind (:entity/:relation/:intent)
   - type/parent (keyword or string, or nil to clear)"
  [{:keys [node store penholder allowed-penholders] :as req}]
  (with-error-handling
    (require-keys! req #{:node :store :penholder :allowed-penholders :type/id :type/kind})
    (let [type-id (:type/id req)
          kind (:type/kind req)
          parent (:type/parent req)
          tx-op (types/set-parent-tx {:node node
                                      :kind kind
                                      :type-id type-id
                                      :parent parent})
          result (pipeline/run-write!
                  {:store store
                   :penholder penholder
                   :allowed-penholders allowed-penholders
                   :model {:type/id type-id :type/kind kind}
                   :required-keys #{:type/id :type/kind}
                   :identity nil
                   :tx-ops [tx-op]
                   :claim {:op :types/parent}
                   :detail {:type/id type-id :type/kind kind :type/parent parent}})]
      (ok {:tx-id (:tx-id result)
           :path/id (get-in result [:path :path/id])}))))

(defn types-merge
  "Merge aliases into a type doc.

   Expects:
   - node
   - store
   - penholder
   - allowed-penholders
   - type/id
   - type/kind
   - type/aliases (vector of keywords/strings)"
  [{:keys [node store penholder allowed-penholders] :as req}]
  (with-error-handling
    (require-keys! req #{:node :store :penholder :allowed-penholders :type/id :type/kind :type/aliases})
    (when-not (sequential? (:type/aliases req))
      (throw (ex-info "type/aliases must be sequential"
                      {:error (mv/layer4-error :invalid-type-aliases
                                               {:type/aliases (:type/aliases req)})})))
    (let [type-id (:type/id req)
          kind (:type/kind req)
          aliases (:type/aliases req)
          tx-op (types/merge-aliases-tx {:node node
                                         :kind kind
                                         :type-id type-id
                                         :aliases aliases})
          result (pipeline/run-write!
                  {:store store
                   :penholder penholder
                   :allowed-penholders allowed-penholders
                   :model {:type/id type-id :type/kind kind}
                   :required-keys #{:type/id :type/kind}
                   :identity nil
                   :tx-ops [tx-op]
                   :claim {:op :types/merge}
                   :detail {:type/id type-id :type/kind kind :type/aliases aliases}})]
      (ok {:tx-id (:tx-id result)
           :path/id (get-in result [:path :path/id])}))))

(defn entity-by-id
  "Fetch an entity/doc by XTDB id.

   Expects:
   - node
   - id"
  [{:keys [node id]}]
  (with-error-handling
    (require-keys! {:node node :id id} #{:node :id})
    (let [db (xtdb/db node)
          ent (when (some? id) (xtdb/entity db id))]
      (if ent
        (ok {:entity ent})
        {:status 404
         :body {:error {:reason :not-found
                        :entity/id id}}}))))

(defn entity-by-external
  "Lookup an entity by (source, external-id).

   Expects:
   - node
   - source (string)
   - external-id (string)"
  [{:keys [node source external-id]}]
  (with-error-handling
    (require-keys! {:node node :source source :external-id external-id} #{:node :source :external-id})
    (let [src (id/normalize-source source)
          ext (id/normalize-external-id external-id)]
      (when-not (and src ext)
        (throw (ex-info "source and external-id required"
                        {:error (mv/layer4-error :missing-required
                                                 {:required #{:source :external-id}
                                                  :source source
                                                  :external-id external-id})})))
      ;; Query for all matching identity mapping docs. This detects ambiguity
      ;; when the store is corrupted (multiple identity docs for the same key).
      (let [db (xtdb/db node)
            q '{:find [e]
                :in [source external]
                :where [[e :identity/source source]
                        [e :identity/external-id external]]}
            matches (vec (xtdb/q db q src ext))]
        (cond
          (empty? matches)
          {:status 404
           :body {:error {:reason :not-found
                          :identity {:source src :external-id ext}}}}

          (< 1 (count matches))
          (throw (ex-info "external-id ambiguous"
                          {:error (id/layer1-error :external-id-ambiguous
                                                   {:identity {:source src :external-id ext}
                                                    :candidates (mapv first matches)})}))

          :else
          (let [idx-id (ffirst matches)
                idx-doc (xtdb/entity db idx-id)
                ent-id (:identity/entity-id idx-doc)
                ent (when ent-id (xtdb/entity db ent-id))]
            (if ent
              (ok {:entity ent})
              {:status 404
               :body {:error {:reason :not-found
                              :identity {:source src :external-id ext}
                              :identity/doc idx-doc}}})))))))

(defn compat-entity
  "Futon1 API compatibility: GET /entity/:id (and /api/alpha/entity/:id).

   Success shape:
   {:profile P :entity {:id .. :name .. :type .. :source ..}}"
  [{:keys [node id profile]}]
  (with-error-handling
    (require-keys! {:node node :id id} #{:node :id})
    (let [doc (f1g/fetch-entity node id)]
      (if doc
        (ok {:profile (or profile "default")
             :entity (f1g/normalize-entity doc)})
        {:status 404
         :body {:error "Entity not found"
                :profile (or profile "default")
                :entity-id id}}))))

(defn compat-entities-latest
  "Futon1 API compatibility: GET /entities/latest.

   Query:
   - type (string like \"pattern/library\")
   - limit (long)
  "
  [{:keys [node type limit profile]}]
  (with-error-handling
    (require-keys! {:node node :type type} #{:node :type})
    (let [type-kw (if (string? type) (keyword type) type)
          {:keys [entities]} (f1g/entities-latest node {:type type-kw :limit limit})]
      (ok {:profile (or profile "default")
           :type (if (keyword? type-kw) (subs (str type-kw) 1) (str type-kw))
           :entities entities}))))

(defn compat-ego
  "Futon1 API compatibility: GET /ego/:name."
  [{:keys [node name profile]}]
  (with-error-handling
    (require-keys! {:node node :name name} #{:node :name})
    (ok {:command "/ego"
         :name name
         :profile (or profile "default")
         :ego (f1g/ego node name)})))

(defn compat-meta-model
  "Futon1 API compatibility: GET /meta/model (patterns descriptor)."
  [{:keys [node scope profile]}]
  (with-error-handling
    (require-keys! {:node node :scope scope} #{:node :scope})
    (if-let [m (f1m/describe-scope node scope)]
      (ok (assoc m :profile (or profile "default")))
      {:status 404
       :body {:error "Model descriptor not found"
              :profile (or profile "default")
              :scope scope}})))

(defn compat-meta-model-verify
  "Futon1 API compatibility: GET /meta/model/<scope>/verify."
  [{:keys [node scope profile]}]
  (with-error-handling
    (require-keys! {:node node :scope scope} #{:node :scope})
    (ok (assoc (verify/verify-scope node scope)
               :profile (or profile "default")))))

(defn compat-patterns-registry
  "Futon1 API compatibility: GET /patterns/registry."
  [{:keys [node profile]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (ok {:profile (or profile "default")
         :registry (f1g/patterns-registry node)})))

(defn compat-ensure-entity
  "Futon1 API compatibility: POST /entity (under /api/alpha).

   Accepts Futon1-shaped JSON entity payload and writes Futon1 graph-memory docs."
  [{:keys [node store penholder allowed-penholders profile payload]}]
  (with-error-handling
    (require-keys! {:node node :store store :penholder penholder :allowed-penholders allowed-penholders} #{:node :store :penholder :allowed-penholders})
    (require-keys! payload #{:name :type})
    (let [{:keys [doc entity]} (f1w/ensure-entity-doc {:node node :payload payload})
          ;; Use the entity doc itself as the L4/L2 model to keep the pipeline honest.
          result (pipeline/run-write!
                  {:store store
                   :penholder penholder
                   :allowed-penholders allowed-penholders
                   :model (dissoc doc :xt/id)
                   :required-keys #{:entity/id :entity/name :entity/type}
                   :identity nil
                   :tx-ops [[:xtdb.api/put doc]]
                   :claim {:op :compat/futon1-entity}
                   :detail {:name (:name payload) :type (:type payload)}})]
      (ok {:profile (or profile "default")
           :entity entity
           :tx-id (:tx-id result)
           :path/id (get-in result [:path :path/id])}))))

(defn compat-upsert-relation
  "Futon1 API compatibility: POST /relation (under /api/alpha)."
  [{:keys [node store penholder allowed-penholders profile payload]}]
  (with-error-handling
    (require-keys! {:node node :store store :penholder penholder :allowed-penholders allowed-penholders} #{:node :store :penholder :allowed-penholders})
    (require-keys! payload #{:type :src :dst})
    (let [payload (if (and (map? (:props payload)) (nil? (:provenance payload)))
                    ;; Futon4's arxana-store uses {:props {:label \"...\" ...}} for relations.
                    ;; Normalize into Futon1-style provenance so downstream readers can
                    ;; surface the label and extra props consistently.
                    (let [props (:props payload)]
                      (assoc payload :provenance {:note (get props :label)
                                                  :props props}))
                    payload)
          {:keys [doc relation]} (f1w/upsert-relation-doc {:node node :payload payload})
          ;; Pipeline integrity check uses :relation/from/:relation/to, so include them in model.
          model (select-keys doc [:relation/id :relation/from :relation/to])
          result (pipeline/run-write!
                  {:store store
                   :penholder penholder
                   :allowed-penholders allowed-penholders
                   :model model
                   :required-keys #{:relation/id :relation/from :relation/to}
                   :identity nil
                   :tx-ops [[:xtdb.api/put doc]]
                   :claim {:op :compat/futon1-relation}
                   :detail {:type (:type payload)}})]
      (ok {:profile (or profile "default")
           :relation relation
           :tx-id (:tx-id result)
           :path/id (get-in result [:path :path/id])}))))

(defn compat-upsert-relations-batch
  "Futon1 API compatibility: POST /relations/batch (under /api/alpha).

  Accepts {:relations [<relation-payload> ...]} where each payload matches the
  /relation shape (as used by futon4's arxana-store).
  Writes all relations in one XTDB transaction."
  [{:keys [node store penholder allowed-penholders profile payload]}]
  (with-error-handling
    (require-keys! {:node node :store store :penholder penholder :allowed-penholders allowed-penholders} #{:node :store :penholder :allowed-penholders})
    (require-keys! payload #{:relations})
    (let [rels (:relations payload)]
      (when-not (and (sequential? rels) (seq rels))
        (throw (ex-info "relations must be a non-empty list"
                        {:error (mv/layer4-error :invalid-relations-batch
                                                 {:expected :non-empty-seq
                                                  :got (type rels)})})))
      (let [normalized (mapv (fn [r]
                               (when-not (map? r)
                                 (throw (ex-info "relation must be a map"
                                                 {:error (mv/layer4-error :invalid-relations-batch
                                                                          {:expected :map
                                                                           :got (type r)})})))
                               (cond
                                 (and (map? (:props r)) (nil? (:provenance r)))
                                 (assoc r :provenance {:note (get (:props r) :label)
                                                       :props (:props r)})
                                 :else r))
                             rels)
            built (mapv (fn [r]
                          (require-keys! r #{:type :src :dst})
                          (f1w/upsert-relation-doc {:node node :payload r}))
                        normalized)
            docs (mapv :doc built)
            public (mapv :relation built)
            ;; Open-world ingest requires endpoint entities be present in the request.
            ;; For batch relation upserts, we derive them from store state.
            endpoint-ids (->> docs
                              (mapcat (fn [d] [(:relation/from d) (:relation/to d)]))
                              (remove nil?)
                              (map str)
                              (distinct)
                              (vec))
            endpoint-docs (mapv (fn [eid]
                                  (or (f1g/fetch-entity node eid)
                                      (throw (ex-info "relation endpoint missing"
                                                      {:error (futon1a.core.invariants/layer2-error :missing-endpoint
                                                                              {:missing eid})}))))
                                endpoint-ids)
            entities (mapv (fn [d]
                             {:entity/id (or (:entity/id d) (:xt/id d))
                              :entity/type (or (:entity/type d) (:type d))})
                           endpoint-docs)
            rel-descriptors (mapv (fn [doc]
                                    {:relation/id (:relation/id doc)
                                     :relation/type (:relation/type doc)
                                     :relation/from (:relation/from doc)
                                     :relation/to (:relation/to doc)})
                                  docs)
            tx-ops (mapv (fn [d] [:xtdb.api/put d]) docs)
            result (pipeline/run-open-world!
                    {:store store
                     :penholder penholder
                     :allowed-penholders allowed-penholders
                     :entities entities
                     :relations rel-descriptors
                     :require-model? false
                     :tx-ops tx-ops
                     :claim {:op :compat/futon1-relations-batch}
                     :detail {:count (count docs)}})]
        (ok {:profile (or profile "default")
             :count (count public)
             :relations public
             :tx-id (:tx-id result)
             :path/id (get-in result [:path :path/id])})))))

(defn- normalize-type
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (-> v str str/trim)
                      s (if (str/starts-with? s ":") (subs s 1) s)]
                  (when (seq s) (keyword s)))
    :else nil))

(defn- nonblank-str [v]
  (let [s (when (some? v) (str v))
        s (some-> s str/trim)]
    (when (seq s) s)))

(defn- sha-from-id
  [id]
  (when-let [s (nonblank-str id)]
    (when (str/includes? s "/")
      (last (clojure.string/split s #"/")))))

(defn upsert-media-lyrics
  "Arxana media surface: upsert lyrics (and optionally track + relation).

  Endpoint lives at:
  - POST /api/media/lyrics
  - POST /api/alpha/media/lyrics

  Payload (EDN or JSON):
  {:track {...}?
   :lyrics {:id \"arxana/media-lyrics/...\" :name ... :type ... :source ... :media/sha256 ...}
   :relation {...}?}
  "
  [{:keys [store penholder allowed-penholders payload profile]}]
  (with-error-handling
    (require-keys! {:store store :penholder penholder :allowed-penholders allowed-penholders :payload payload}
                   #{:store :penholder :allowed-penholders :payload})
    (require-keys! payload #{:lyrics})
    (let [{:keys [track lyrics relation]} payload
          lyrics-id (or (nonblank-str (:id lyrics))
                        (nonblank-str (:entity/id lyrics))
                        (nonblank-str (:external-id lyrics))
                        (nonblank-str (:entity/external-id lyrics))
                        (nonblank-str (:name lyrics)))
          lyrics-type (normalize-type (or (:type lyrics) (:entity/type lyrics)))
          lyrics-name (nonblank-str (:name lyrics))
          lyrics-src (or (nonblank-str (:source lyrics))
                         (nonblank-str (:entity/source lyrics)))
          lyrics-sha (or (nonblank-str (:media/sha256 lyrics))
                         (sha-from-id lyrics-id))
          _ (when-not (and lyrics-id (= :arxana/media-lyrics lyrics-type) lyrics-src lyrics-sha)
              (throw (ex-info "invalid lyrics payload"
                              {:error (mv/layer4-error :invalid-media-lyrics
                                                       {:expected {:lyrics/type :arxana/media-lyrics
                                                                   :lyrics/id :nonblank
                                                                   :lyrics/source :nonblank
                                                                   :lyrics/media.sha256 :nonblank}
                                                        :got {:lyrics/id lyrics-id
                                                              :lyrics/type lyrics-type
                                                              :lyrics/source (boolean lyrics-src)
                                                              :lyrics/media/sha256 lyrics-sha}})})))
          lyrics-doc {:xt/id lyrics-id
                      :entity/id lyrics-id
                      :entity/type :arxana/media-lyrics
                      :entity/name (or lyrics-name (str lyrics-id))
                      :entity/external-id lyrics-id
                      :entity/source lyrics-src
                      :media/sha256 lyrics-sha}
          track-id (or (nonblank-str (:id track))
                       (nonblank-str (:entity/id track))
                       (nonblank-str (:external-id track))
                       (nonblank-str (:entity/external-id track))
                       (nonblank-str (:name track)))
          track-type (normalize-type (or (:type track) (:entity/type track)))
          track-name (nonblank-str (:name track))
          track-doc (when track-id
                      {:xt/id track-id
                       :entity/id track-id
                       :entity/type (or track-type :arxana/media-track)
                       :entity/name (or track-name (str track-id))
                       :entity/external-id track-id})
          rel-type (normalize-type (or (:type relation) (:relation/type relation) :media/lyrics))
          rel-id (or (nonblank-str (:id relation))
                     (nonblank-str (:relation/id relation))
                     (when (and track-id lyrics-id)
                       (str "rel|" track-id "|" (subs (str rel-type) 1) "|" lyrics-id)))
          rel-doc (when (and track-id lyrics-id rel-id)
                    {:xt/id rel-id
                     :relation/id rel-id
                     :relation/type rel-type
                     :relation/from track-id
                     :relation/to lyrics-id
                     :relation/src track-id
                     :relation/dst lyrics-id
                     :relation/provenance (when (map? (:provenance relation)) (:provenance relation))
                     :relation/confidence (:confidence relation)
                     :relation/last-seen (:last-seen relation)})
          docs (cond-> [lyrics-doc]
                 track-doc (conj track-doc)
                 rel-doc (conj rel-doc))
          entities (->> docs
                        (filter :entity/id)
                        (mapv (fn [d] {:entity/id (:entity/id d) :entity/type (:entity/type d)})))
          relations (if rel-doc
                      [{:relation/id (:relation/id rel-doc)
                        :relation/type (:relation/type rel-doc)
                        :relation/from (:relation/from rel-doc)
                        :relation/to (:relation/to rel-doc)}]
                      [])
          tx-ops (mapv (fn [d] [:xtdb.api/put d]) docs)
          result (pipeline/run-open-world!
                  {:store store
                   :penholder penholder
                   :allowed-penholders allowed-penholders
                   :entities entities
                   :relations relations
                   :require-model? false
                   :tx-ops tx-ops
                   :claim {:op :media/lyrics-upsert}
                   :detail {:lyrics/id lyrics-id
                            :track/id track-id
                            :relation/id rel-id}})]
      (ok {:profile (or profile "default")
           :counts (:counts result)
           :tx-id (:tx-id result)
           :path/id (get-in result [:path :path/id])
           :lyrics {:id lyrics-id}}))))

(defn ingest
  "Ingest open-world data.

   Expects:
   - store
   - penholder
   - allowed-penholders
   - entities (vector)
   - relations (vector)
   - tx-ops (vector)
   - require-model? (optional)"
  [req]
  (with-error-handling
    (require-keys! req #{:store :penholder :allowed-penholders :entities :relations :tx-ops})
    (ok (pipeline/run-open-world! req))))

(defn repair
  "Repair entities.

   Expects:
   - entities (vector)
   - repair-fn (optional)
   - dry-run? (optional)"
  [req]
  (with-error-handling
    (require-keys! req #{:entities})
    (ok (repair/repair-entities! req))))

(defn verify-repair
  "Verify repair results.

   Expects:
   - prev, next, label
   - errors (optional)"
  [req]
  (with-error-handling
    (require-keys! req #{:prev :next :label})
    (ok (repair/verify-repair! req))))
