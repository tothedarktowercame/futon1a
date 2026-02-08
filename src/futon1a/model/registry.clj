(ns futon1a.model.registry
  "Model/schema registry.

   Tension:  Schema evolution vs contract stability (T8).
   Pattern:  storage/schema-evolution-stability"
  (:require [clojure.set :as set]
            [futon1a.model.descriptor-store :as dstore]))

(defonce registry (atom {}))

(defn clear-registry!
  []
  (reset! registry {}))

(defn- normalize-descriptor
  [descriptor]
  (when-not (map? descriptor)
    (throw (ex-info "descriptor must be a map" {:descriptor descriptor})))
  ;; Prototype 2: allow full futon1-style descriptor shape (Section 2.11.1).
  (if (and (contains? descriptor :model/scope)
           (contains? descriptor :schema/version)
           (contains? descriptor :schema/certificate)
           (contains? descriptor :entities))
    (dstore/normalize-descriptor descriptor)
    (let [fields (:fields descriptor)
          required (:required descriptor)
          fields (or fields required)
          required (or required fields)]
      (when-not (seq fields)
        (throw (ex-info "descriptor requires :fields or :required" {:descriptor descriptor})))
      (let [fields (vec fields)
            required (set required)]
        (when-not (every? keyword? fields)
          (throw (ex-info "descriptor fields must be keywords" {:fields fields})))
        (when-not (every? keyword? required)
          (throw (ex-info "descriptor required fields must be keywords" {:required required})))
        (when-not (set/subset? required (set fields))
          (throw (ex-info "required must be subset of fields"
                          {:fields fields :required required})))
        (assoc descriptor :fields fields :required required)))))

(defn register-model!
  "Register a model descriptor.

   Inputs:
   - id (keyword)
   - descriptor (map)
   - override? (boolean, optional)
  "
  [{:keys [id descriptor override?]}]
  (when-not (keyword? id)
    (throw (ex-info "model id must be keyword" {:id id})))
  (let [descriptor (normalize-descriptor descriptor)]
    (when (and (contains? @registry id) (not override?))
      (throw (ex-info "model already registered" {:id id})))
    (swap! registry assoc id descriptor)
    {:ok? true :id id :descriptor descriptor}))

(defn get-model
  [id]
  (get @registry id))

(defn list-models
  []
  (keys @registry))
