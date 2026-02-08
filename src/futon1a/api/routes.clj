(ns futon1a.api.routes
  "Minimal API surface for futon1a.

   Pattern:  storage/canonical-interface
   Theory:   futon-theory/error-hierarchy (all handlers use with-error-handling)"
  (:require [futon1a.api.errors :as errors]
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
