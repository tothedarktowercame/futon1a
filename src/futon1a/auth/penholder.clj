(ns futon1a.auth.penholder
  "Layer 3 authorization gate (penholder enforcement)."
  (:require [clojure.string :as str]))

(defn layer3-error
  [reason context]
  {:error/layer 3
   :error/status 403
   :error/reason reason
   :error/context context})

(defn normalize-penholder
  [s]
  (let [v (when (string? s) (str/trim s))]
    (when (seq v) v)))

(defn authorize!
  "Authorize a request based on penholder.

   Inputs:
   - penholder (string)
   - allowed-penholders (set of strings)

   Returns {:ok? true} or throws.
  "
  [{:keys [penholder allowed-penholders]}]
  (let [ph (normalize-penholder penholder)]
    (when-not ph
      (throw (ex-info "penholder required" {:error (layer3-error :missing-penholder {})})))
    (when-not (contains? allowed-penholders ph)
      (throw (ex-info "penholder forbidden"
                      {:error (layer3-error :forbidden {:penholder ph})})))
    {:ok? true :penholder ph}))
