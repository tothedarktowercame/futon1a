(ns futon1a.api.routes
  "Minimal API surface for futon1a.

   Pattern:  storage/canonical-interface
   Theory:   futon-theory/error-hierarchy (all handlers use with-error-handling)"
  (:require [clojure.string :as str]
            [futon1a.api.errors :as errors]
            [futon1a.compat.futon1-docbook :as f1d]
            [futon1a.compat.futon1-graph :as f1g]
            [futon1a.compat.futon1-model :as f1m]
            [futon1a.compat.futon1-write :as f1w]
            [futon1a.diag.health :as health]
            [futon1a.core.identity :as id]
            [futon1a.core.invariants :as inv]
            [futon1a.model.descriptor-store :as dstore]
            [futon1a.model.registry :as registry]
            [futon1a.model.type-registry :as types]
            [futon1a.model.validation :as mv]
            [futon1a.model.verify :as verify]
            [futon1a.scripts.repair :as repair]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.api.snapshot :as snapshot]
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
  [{:keys [node name profile fold? depth]}]
  (with-error-handling
    (require-keys! {:node node :name name} #{:node :name})
    (let [ego (or (when fold?
                    (f1g/folded-ego node name {:depth (or depth 1)}))
                  (f1g/ego node name))]
      (ok {:command "/ego"
           :name name
           :profile (or profile "default")
           :ego ego}))))

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

;; --- Docbook parity endpoints -------------------------------------------------

(defn- truthy-param?
  [value]
  (contains? #{"1" "true" "yes" "on"}
             (some-> value str str/lower-case)))

(defn compat-docbook-contents
  "Futon1/Futon4 compatibility: GET /api/alpha/docs/:book/contents."
  [{:keys [node book]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (ok (f1d/contents node book))))

(defn compat-docbook-toc
  "Futon1/Futon4 compatibility: GET /api/alpha/docs/:book/toc."
  [{:keys [node book]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (ok (f1d/toc node book))))

(defn compat-docbook-heading
  "Futon1/Futon4 compatibility: GET /api/alpha/docs/:book/heading/:doc-id."
  [{:keys [node book doc-id]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (if-let [data (f1d/heading+entries node book doc-id)]
      (ok data)
      {:status 404
       :body {:error "Heading not found"
              :book (f1d/normalize-book book)
              :doc-id doc-id}})))

(defn compat-docbook-recent
  "Futon1/Futon4 compatibility: GET /api/alpha/docs/:book/recent."
  [{:keys [node book limit]}]
  (with-error-handling
    (require-keys! {:node node} #{:node})
    (ok (f1d/recent-entries node book limit))))

(defn compat-docbook-update-order
  "Futon1/Futon4 compatibility: POST /api/alpha/docs/:book/contents/order."
  [{:keys [node store penholder allowed-penholders book payload]}]
  (with-error-handling
    (require-keys! {:node node
                    :store store
                    :penholder penholder
                    :allowed-penholders allowed-penholders
                    :payload payload}
                   #{:node :store :penholder :allowed-penholders :payload})
    (let [order (:order payload)
          source (:source payload)
          timestamp (:timestamp payload)]
      (if-not (and (sequential? order) (not (string? order)))
        {:status 400
         :body {:error "order must be a list of doc-ids"
                :book (f1d/normalize-book book)}}
        (let [{:keys [tx-ops response]} (f1d/prepare-update-toc-order node book {:order order
                                                                                  :source source
                                                                                  :timestamp timestamp})
              write-result (pipeline/run-write! {:store store
                                                 :penholder penholder
                                                 :allowed-penholders allowed-penholders
                                                 :model {}
                                                 :identity nil
                                                 :tx-ops tx-ops
                                                 :claim {:op :compat/futon1-docbook-order}
                                                 :detail {:book (f1d/normalize-book book)
                                                          :count (count order)}})]
          (ok (assoc response
                     :tx-id (:tx-id write-result)
                     :path/id (get-in write-result [:path :path/id]))))))))

(defn compat-docbook-entry
  "Futon1/Futon4 compatibility: POST /api/alpha/docs/:book/entry."
  [{:keys [store penholder allowed-penholders book payload]}]
  (with-error-handling
    (require-keys! {:store store
                    :penholder penholder
                    :allowed-penholders allowed-penholders
                    :payload payload}
                   #{:store :penholder :allowed-penholders :payload})
    (if-not (map? payload)
      {:status 400
       :body {:error "Expected entry payload object"
              :book (f1d/normalize-book book)}}
      (let [result (f1d/prepare-upsert-entry book payload)]
        (if-not (:ok? result)
          {:status 400
           :body (dissoc result :ok? :tx-ops)}
          (let [tx-ops (:tx-ops result)
                write-result (pipeline/run-write! {:store store
                                                   :penholder penholder
                                                   :allowed-penholders allowed-penholders
                                                   :model (select-keys (:entry result)
                                                                       [:doc/id :doc/entry-id :doc/book])
                                                   :required-keys #{:doc/id :doc/entry-id :doc/book}
                                                   :identity nil
                                                   :tx-ops tx-ops
                                                   :claim {:op :compat/futon1-docbook-entry}
                                                   :detail {:book (f1d/normalize-book book)
                                                            :doc/id (:doc-id result)
                                                            :entry-id (:entry-id result)}})
                body (assoc (dissoc result :ok? :tx-ops)
                            :tx-id (:tx-id write-result)
                            :path/id (get-in write-result [:path :path/id]))]
            (ok body)))))))

(defn compat-docbook-entries
  "Futon1/Futon4 compatibility: POST /api/alpha/docs/:book/entries."
  [{:keys [store penholder allowed-penholders book payload]}]
  (with-error-handling
    (require-keys! {:store store
                    :penholder penholder
                    :allowed-penholders allowed-penholders
                    :payload payload}
                   #{:store :penholder :allowed-penholders :payload})
    (let [entries (cond
                    (sequential? payload) payload
                    (and (map? payload) (sequential? (:entries payload))) (:entries payload)
                    :else nil)]
      (if-not (sequential? entries)
        {:status 400
         :body {:error "Expected entries list"
                :book (f1d/normalize-book book)}}
        (let [result (f1d/prepare-upsert-entries book entries)
              tx-ops (:tx-ops result)
              write-result (when (seq tx-ops)
                             (pipeline/run-write! {:store store
                                                   :penholder penholder
                                                   :allowed-penholders allowed-penholders
                                                   :model {}
                                                   :identity nil
                                                   :tx-ops tx-ops
                                                   :claim {:op :compat/futon1-docbook-entries}
                                                   :detail {:book (f1d/normalize-book book)
                                                            :count (count entries)}}))
              body (cond-> (dissoc result :ok? :tx-ops)
                     write-result
                     (assoc :tx-id (:tx-id write-result)
                            :path/id (get-in write-result [:path :path/id])))]
          (if (:ok? result)
            (ok body)
            {:status 400 :body body}))))))

(defn compat-docbook-delete-doc
  "Futon1/Futon4 compatibility: DELETE /api/alpha/docs/:book/doc/:doc-id."
  [{:keys [node store penholder allowed-penholders book doc-id]}]
  (with-error-handling
    (require-keys! {:node node
                    :store store
                    :penholder penholder
                    :allowed-penholders allowed-penholders}
                   #{:node :store :penholder :allowed-penholders})
    (if-not (seq (str doc-id))
      {:status 400
       :body {:error "doc-id required"
              :book (f1d/normalize-book book)}}
      (let [{:keys [deleted tx-ops] :as plan} (f1d/plan-delete-doc node book doc-id)]
        (if (pos? deleted)
          (let [write-result (pipeline/run-write! {:store store
                                                   :penholder penholder
                                                   :allowed-penholders allowed-penholders
                                                   :counter-ratchet {:allow-drop-classes #{:docbook}}
                                                   :model {}
                                                   :identity nil
                                                   :tx-ops tx-ops
                                                   :claim {:op :compat/futon1-docbook-delete-doc}
                                                   :detail {:book (f1d/normalize-book book)
                                                            :doc/id doc-id
                                                            :deleted deleted}})]
            (ok (-> plan
                    (dissoc :tx-ops)
                    (assoc :tx-id (:tx-id write-result)
                           :path/id (get-in write-result [:path :path/id])))))
          {:status 404
           :body (assoc (dissoc plan :tx-ops) :error "Doc not found")})))))

(defn compat-docbook-delete-toc
  "Futon1/Futon4 compatibility: DELETE /api/alpha/docs/:book/toc/:doc-id."
  [{:keys [node store penholder allowed-penholders book doc-id cascade?]}]
  (with-error-handling
    (require-keys! {:node node
                    :store store
                    :penholder penholder
                    :allowed-penholders allowed-penholders}
                   #{:node :store :penholder :allowed-penholders})
    (if-not (seq (str doc-id))
      {:status 400
       :body {:error "doc-id required"
              :book (f1d/normalize-book book)}}
      (let [cascade? (if (string? cascade?)
                       (truthy-param? cascade?)
                       (boolean cascade?))
            {:keys [deleted tx-ops] :as plan} (f1d/plan-delete-toc node book doc-id {:cascade? cascade?})]
        (if (pos? deleted)
          (let [write-result (pipeline/run-write! {:store store
                                                   :penholder penholder
                                                   :allowed-penholders allowed-penholders
                                                   :counter-ratchet {:allow-drop-classes #{:docbook}}
                                                   :model {}
                                                   :identity nil
                                                   :tx-ops tx-ops
                                                   :claim {:op :compat/futon1-docbook-delete-toc}
                                                   :detail {:book (f1d/normalize-book book)
                                                            :doc/id doc-id
                                                            :deleted deleted
                                                            :cascade? cascade?}})]
            (ok (-> plan
                    (dissoc :tx-ops)
                    (assoc :tx-id (:tx-id write-result)
                           :path/id (get-in write-result [:path :path/id])))))
          {:status 404
           :body (assoc (dissoc plan :tx-ops) :error "Doc not found")})))))

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
                                                      {:error (inv/layer2-error :missing-endpoint
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

(defn compat-write-evidence
  "Futon1a API: POST /api/alpha/evidence.

   Writes evidence entries through the canonical pipeline so successful writes
   always emit a proof-path id."
  [{:keys [node store penholder allowed-penholders payload]}]
  (with-error-handling
    (require-keys! {:node node
                    :store store
                    :penholder penholder
                    :allowed-penholders allowed-penholders
                    :payload payload}
                   #{:node :store :penholder :allowed-penholders :payload})
    (let [eid (or (:evidence/id payload) (:id payload) (str (java.util.UUID/randomUUID)))
          at (or (:evidence/at payload) (:at payload) (.toString (java.time.Instant/now)))
          etype (normalize-type (or (:evidence/type payload) (:type payload)))
          ctype (normalize-type (or (:evidence/claim-type payload) (:claim-type payload)))
          author (or (:evidence/author payload) (:author payload))
          ebody (or (:evidence/body payload) (:body payload))
          subject (or (:evidence/subject payload) (:subject payload))]
      (cond
        (not etype)
        {:status 400 :body {:error "evidence/type required"}}

        (not ctype)
        {:status 400 :body {:error "evidence/claim-type required"}}

        (not author)
        {:status 400 :body {:error "evidence/author required"}}

        (xtdb/entity (xtdb/db node) eid)
        {:status 409 :body {:error "duplicate evidence id" :evidence/id eid}}

        :else
        (let [entry (cond-> {:xt/id eid
                             :evidence/id eid
                             :evidence/at at
                             :evidence/type etype
                             :evidence/claim-type ctype
                             :evidence/author author
                             :evidence/body (or ebody {})
                             :evidence/tags (vec (or (:evidence/tags payload) (:tags payload) []))}
                      subject
                      (assoc :evidence/subject subject)
                      (or (:evidence/pattern-id payload) (:pattern-id payload))
                      (assoc :evidence/pattern-id (or (:evidence/pattern-id payload) (:pattern-id payload)))
                      (or (:evidence/session-id payload) (:session-id payload))
                      (assoc :evidence/session-id (or (:evidence/session-id payload) (:session-id payload)))
                      (or (:evidence/in-reply-to payload) (:in-reply-to payload))
                      (assoc :evidence/in-reply-to (or (:evidence/in-reply-to payload) (:in-reply-to payload)))
                      (or (:evidence/fork-of payload) (:fork-of payload))
                      (assoc :evidence/fork-of (or (:evidence/fork-of payload) (:fork-of payload)))
                      (some? (or (:evidence/conjecture? payload) (:conjecture? payload)))
                      (assoc :evidence/conjecture? (boolean (or (:evidence/conjecture? payload) (:conjecture? payload))))
                      (some? (or (:evidence/ephemeral? payload) (:ephemeral? payload)))
                      (assoc :evidence/ephemeral? (boolean (or (:evidence/ephemeral? payload) (:ephemeral? payload))))
                      )
                result (pipeline/run-write! {:store store
                                             :penholder penholder
                                             :allowed-penholders allowed-penholders
                                             :model {}
                                             :identity nil
                                             :tx-ops [[:xtdb.api/put entry]]
                                             :claim {:op :compat/evidence-write}
                                             :detail {:evidence/id eid}})]
          {:status 201
           :body {:ok true
                  :evidence/id eid
                  :entry (dissoc entry :xt/id)
                  :tx-id (:tx-id result)
                  :path/id (get-in result [:path :path/id])}})))))

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
                                                              :lyrics/media.sha256 lyrics-sha}})})))
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

(defn snapshot-save
  "Futon4 support: POST /snapshot (under /api/alpha or /api).

   This writes a filesystem snapshot under the system's data-dir, not XTDB.

   Expects:
   - node
   - data-dir
   - scope (\"all\"|\"latest\")
   - label (optional)"
  [{:keys [node data-dir scope label] :as req}]
  (with-error-handling
    (require-keys! req #{:node :data-dir})
    (ok (snapshot/export-graph-snapshot {:node node
                                         :data-dir data-dir
                                         :scope scope
                                         :label label}))))

(defn snapshot-restore
  "Futon4 support: POST /snapshot/restore (under /api/alpha or /api).

   Restore merges graph docs from a snapshot file into XTDB through the
   open-world pipeline (preserving L0-L3 gates).

   Expects:
   - store
   - penholder
   - allowed-penholders
   - data-dir
   - scope (\"all\"|\"latest\")
   - snapshot/id (optional; defaults to \"latest\" when scope=latest)"
  [{:keys [store penholder allowed-penholders data-dir scope payload] :as req}]
  (with-error-handling
    (require-keys! req #{:store :penholder :allowed-penholders :data-dir})
    (ok (snapshot/restore-graph-snapshot!
         {:store store
          :penholder penholder
          :allowed-penholders allowed-penholders
          :allowed-expansions (:allowed-expansions req)
          :allowed-tooling (:allowed-tooling req)
          :data-dir data-dir
          :scope scope
          :snapshot/id (or (:snapshot/id payload) (:snapshot/id req) (:snapshot/id (or payload {})))}))))

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

;; --- Hyperedge endpoints -----------------------------------------------------

(defn- coerce-valid-time
  "Coerce a payload `hx/valid-time` directive to a `java.util.Date` (the
   valid-time START XTDB stamps onto the put op's 3rd element), or nil.

   Accepts an epoch-millis number, a numeric string, or an ISO-8601
   instant string. Used by M-populate-substrate-2 D3 to write code
   structure at a commit's timestamp so `db-as-of(t)` time-travels it.

   Returns nil for nil/blank/unparseable input — an unparseable
   valid-time degrades to a current-time put rather than failing the
   write (the directive is advisory; a malformed clock must not lose the
   doc)."
  [vt]
  (cond
    (nil? vt) nil
    (inst? vt) vt
    (number? vt) (java.util.Date. (long vt))
    (string? vt) (let [s (str/trim vt)]
                   (cond
                     (str/blank? s) nil
                     (re-matches #"\d+" s) (java.util.Date. (Long/parseLong s))
                     :else (try (java.util.Date/from (java.time.Instant/parse s))
                                (catch Exception _ nil))))
    :else nil))

(defn compat-upsert-hyperedge
  "Futon1 API parity: POST /hyperedge (under /api/alpha or /api).

   Accepts both flat string endpoints (futon1a compat):
     {:hx/type \"link/refers-to\" :hx/endpoints [\"eid-a\" \"eid-b\"]}

   And rich Nelson-style endpoint maps (futon1 parity):
     {:hx/type :link/refers-to
      :hx/endpoints [{:role :source :entity {:id \"eid-a\"}}
                      {:role :target :entity {:id \"eid-b\"}}]}

   Optional `:hx/valid-time` (epoch-ms / ISO-8601) stamps the put at a
   past valid-time so `db-as-of` recovers structure as of that instant
   (M-populate-substrate-2 D3). The doc builder ignores the key, so it
   never lands in the stored doc — it only drives the put op's 3rd
   element. A past valid-time remains visible at current time, so the
   pipeline's `verify-materialized!` (current-db read) still passes.

   Optional `:hx/op \"retract\"` ends the hyperedge's validity at
   `:hx/valid-time` instead of putting — `[:xtdb.api/delete hx-id vt]` —
   so `db-as-of(>vt)` no longer sees it (D3 slice 2 removal-accuracy). The
   hx-id is computed from type+endpoints exactly as for the put, so a
   retract targets exactly the entity an earlier put created. A delete-only
   tx has no put-docs, so verify-materialized! passes; the counter-ratchet
   guards only protected classes (entity/relation/descriptor/docbook), which
   a code/* hyperedge is not, so deletes are unguarded.

   Writes via the full pipeline (L4→L0)."
  [{:keys [node store penholder allowed-penholders profile payload]}]
  (with-error-handling
    (require-keys! {:node node :store store :penholder penholder :allowed-penholders allowed-penholders}
                   #{:node :store :penholder :allowed-penholders})
    (let [{:keys [doc hyperedge]} (f1w/upsert-hyperedge-doc {:node node :payload payload})
          valid-time (coerce-valid-time (or (:hx/valid-time payload)
                                            (:valid-time payload)))
          retract? (= "retract" (some-> (or (:hx/op payload) (:op payload))
                                        name str/lower-case))
          tx-op (if retract?
                  (cond-> [:xtdb.api/delete (:xt/id doc)]
                    valid-time (conj valid-time))
                  (cond-> [:xtdb.api/put doc]
                    valid-time (conj valid-time)))
          result (pipeline/run-write!
                  {:store store
                   :penholder penholder
                   :allowed-penholders allowed-penholders
                   :model {}
                   :identity nil
                   :tx-ops [tx-op]
                   :claim {:op :compat/futon1-hyperedge}
                   :detail (cond-> {:hx/type (:hx/type payload)}
                             valid-time (assoc :hx/valid-time valid-time)
                             retract? (assoc :hx/op :retract))})]
      (ok (cond-> {:profile (or profile "default")
                   :hyperedge hyperedge
                   :tx-id (:tx-id result)
                   :path/id (get-in result [:path :path/id])}
            retract? (assoc :retracted? true))))))

(defn hyperedge-by-id
  "GET /api/alpha/hyperedge/:id — fetch a single hyperedge by its :hx/id."
  [{:keys [node id]}]
  (with-error-handling
    (let [db (xtdb/db node)
          doc (when (seq id) (xtdb/entity db id))]
      (if (and doc (:hx/id doc))
        (ok (dissoc doc :xt/id))
        {:status 404
         :body {:error "not found" :hx/id id}}))))

(def ^:private hyperedge-query-timeout-ms
  "Hard cap (ms) on the hyperedge scan queries below. `:hx/type` / `:hx/endpoints`
   are full attribute scans with `(pull e [*])` + in-memory sort, so on a large
   store an unbounded `:limit` (applied post-scan) can run for minutes and, when
   launched on a future that can't be interrupted mid-RocksDB-iteration, burn a
   core indefinitely. A query `:timeout` makes XTDB abort and the caller degrade
   (error response) instead of wedging. Tune via this constant."
  15000)

(defn hyperedges-by-type
  "GET /api/alpha/hyperedges?type=... — query hyperedges by :hx/type.

   Fetches entity ids first (a cheap index lookup — no document
   materialization), sorts the ids, then pulls ONLY the documents that
   are actually returned. The previous implementation pulled `[*]` for
   every matching entity and sorted the whole set before applying the
   limit, which timed out on large types (e.g. code/v05/var has 115k+,
   code/v05/edits 184k+ rows) — silently breaking both inventory counts
   and commit-ingest's edit resolution. See M-populate-substrate-2 D2.

   `:count` is the TRUE total when no repo/source-file filter is given
   (counted from ids without pulling); with a filter it is the count of
   the returned (pulled, post-filtered) docs."
  [{:keys [node hx-type limit repo source-file]}]
  (with-error-handling
    (let [db (xtdb/db node)
          type-kw (when hx-type (normalize-type hx-type))
          _ (when-not type-kw
              (throw (ex-info "type parameter required" {:type hx-type})))
          filtered? (or repo source-file)
          prop-matches? (fn [h k expected]
                          (or (nil? expected)
                              (= expected (get-in h [:hx/props k]))
                              (= expected (get-in h [:hx/props (name k)]))))
          ;; ids only — index lookup, no per-doc pull, no full-set sort cost
          eids (->> (xtdb/q db (assoc '{:find [e] :in [t] :where [[e :hx/type t]]}
                                      :timeout hyperedge-query-timeout-ms)
                             type-kw)
                    (map first)
                    (sort-by str))
          total (count eids)
          ;; pull lazily so a limited query only materializes what it keeps
          docs (cond->> (map #(xtdb/entity db %) eids)
                 repo        (filter #(prop-matches? % :repo repo))
                 source-file (filter #(prop-matches? % :source-file source-file)))
          docs (if (and limit (pos? limit)) (take limit docs) docs)
          results (->> docs (map #(dissoc % :xt/id)) vec)]
      (ok {:hyperedges results
           :count (if filtered? (count results) total)}))))

(def ^:private uuid-pattern
  "Canonical UUID format `8-4-4-4-12` hex string."
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn- resolve-end-id
  "If END-ID is UUID-shaped, look up the corresponding entity and
   return its `:entity/name` (the semantic identifier used by hyperedge
   endpoints). Otherwise return END-ID unchanged.

   Bridges the identifier gap between UUID-keyed entities and
   semantic-string hyperedge endpoints. See README-conventions.md §4
   for design rationale."
  [db end-id]
  (if (and (string? end-id)
           (re-matches uuid-pattern end-id))
    (let [entity (-> (xtdb/q db '{:find [(pull e [:entity/name])]
                                  :in [eid]
                                  :where [[e :entity/id eid]]}
                             end-id)
                     ffirst)
          entity-name (:entity/name entity)]
      (or entity-name end-id))
    end-id))

(defn hyperedges-by-end
  "GET /api/alpha/hyperedges?end=... — query hyperedges that include an endpoint.

   Accepts either a semantic string identifier (the multi-watcher
   writes vertex-id strings like `futon3-d/mission/weird-modernism`
   into `:hx/endpoints`) or a UUID. UUIDs are resolved through the
   entity store to find the corresponding `:entity/name`, then the
   exact-string match runs against that name — so callers can pass
   whichever identifier they hold."
  [{:keys [node end-id limit]}]
  (with-error-handling
    (let [db (xtdb/db node)
          _ (when-not (seq end-id)
              (throw (ex-info "end parameter required" {:end end-id})))
          resolved-id (resolve-end-id db end-id)
          ;; Query :hx/endpoints (flat string vector) for the (possibly
          ;; resolved) endpoint id.
          results (->> (xtdb/q db (assoc '{:find [(pull e [*])]
                                           :in [eid]
                                           :where [[e :hx/endpoints eid]]}
                                         :timeout hyperedge-query-timeout-ms)
                               resolved-id)
                       (map first)
                       (map #(dissoc % :xt/id))
                       (sort-by #(str (:hx/id %)))
                       vec)
          results (if (and limit (pos? limit))
                    (vec (take limit results))
                    results)]
      (ok {:hyperedges results :count (count results)}))))
