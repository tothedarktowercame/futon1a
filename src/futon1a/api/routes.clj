(ns futon1a.api.routes
  "Minimal API surface for futon1a.

   Pattern:  storage/canonical-interface
   Theory:   futon-theory/error-hierarchy (all handlers use with-error-handling)"
  (:require [futon1a.api.errors :as errors]
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
    (let [{:keys [doc relation]} (f1w/upsert-relation-doc {:node node :payload payload})
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
