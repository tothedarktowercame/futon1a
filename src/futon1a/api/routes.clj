(ns futon1a.api.routes
  "Minimal API surface for futon1a (stub)."
  (:require [futon1a.api.errors :as errors]
            [futon1a.diag.health :as health]
            [futon1a.ingest.open-world :as open-world]
            [futon1a.model.registry :as registry]
            [futon1a.model.validation :as mv]
            [futon1a.scripts.repair :as repair]
            [futon1a.core.pipeline :as pipeline]))

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

(defn health
  "Health endpoint. Accepts optional checks/counts metadata."
  ([] (health {}))
  ([opts]
   (ok (health/health-report opts))))

(defn handle-error
  [e]
  (errors/error->response (ex-data e)))

(defn write
  "Stub write handler.

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
  (try
    (let [result (pipeline/run-write! req)]
      (ok {:tx-id (:tx-id result)}))
    (catch clojure.lang.ExceptionInfo e
      (handle-error e))
    (catch Exception e
      {:status 500
       :body {:error {:reason :exception
                      :message (.getMessage e)}}})))

(defn register-model
  "Register a model descriptor.

   Expects:
   - id (keyword)
   - descriptor (map)"
  [req]
  (try
    (require-keys! req #{:id :descriptor})
    (ok (registry/register-model! req))
    (catch clojure.lang.ExceptionInfo e
      (handle-error e))))

(defn list-models
  "List all registered model ids."
  []
  (ok {:models (vec (registry/list-models))}))

(defn ingest
  "Ingest open-world data.

   Expects:
   - entities (vector)
   - relations (vector)
   - require-model? (optional)"
  [req]
  (try
    (require-keys! req #{:entities :relations})
    (ok (open-world/ingest! req))
    (catch clojure.lang.ExceptionInfo e
      (handle-error e))))

(defn repair
  "Repair entities.

   Expects:
   - entities (vector)
   - repair-fn (optional)
   - dry-run? (optional)"
  [req]
  (try
    (require-keys! req #{:entities})
    (ok (repair/repair-entities! req))
    (catch clojure.lang.ExceptionInfo e
      (handle-error e))))

(defn verify-repair
  "Verify repair results.

   Expects:
   - prev, next, label
   - errors (optional)"
  [req]
  (try
    (require-keys! req #{:prev :next :label})
    (ok (repair/verify-repair! req))
    (catch clojure.lang.ExceptionInfo e
      (handle-error e))))
