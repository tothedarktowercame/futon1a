(ns futon1a.api.routes
  "Minimal API surface for futon1a (stub)."
  (:require [futon1a.api.errors :as errors]
            [futon1a.core.pipeline :as pipeline]))

(defn ok
  [body]
  {:status 200
   :body body})

(defn health
  "Simple health endpoint." []
  (ok {:status "ok"}))

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
      (handle-error e))))
