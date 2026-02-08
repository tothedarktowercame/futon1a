(ns futon1a.core.identity
  "Layer 1 identity checks.

   Enforces UUID identity and unique external-id mapping before write."
  (:require [clojure.string :as str])
  (:import (java.util UUID)))

(defn layer1-error
  "Build a Layer 1 error map." [reason context]
  {:error/layer 1
   :error/status 409
   :error/reason reason
   :error/context context})

(defn uuid-string?
  [s]
  (boolean
    (when (string? s)
      (try (UUID/fromString s) true
           (catch Exception _ false)))))

(defn normalize-external-id
  [s]
  (let [v (when (string? s) (str/trim s))]
    (when (seq v) v)))

(defn validate-identity
  "Validate identity inputs and uniqueness.

   Inputs:
   - id (string UUID)
   - external-id (string)
   - source (string)
   - existing-by-external: result of looking up this external-id in the
     store. Pass nil if no conflict; pass the existing entity map if one
     was found. Any truthy value triggers a 409 conflict rejection.

   Returns {:ok? true :id <uuid> :external-id <string>} or throws.
  "
  [{:keys [id external-id source existing-by-external]}]
  (when (and id (not (uuid-string? id)))
    (throw (ex-info "Entity id must be UUID"
                    {:error (layer1-error :invalid-id {:id id})})))
  (let [ext-id (normalize-external-id external-id)]
    (when (and existing-by-external ext-id)
      (throw (ex-info "External id already in use"
                      {:error (layer1-error :external-id-conflict
                                            {:external-id ext-id
                                             :existing existing-by-external
                                             :source source})})))
    {:ok? true
     :id (or id (str (UUID/randomUUID)))
     :external-id ext-id}))
