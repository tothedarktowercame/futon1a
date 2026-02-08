(ns futon1a.model.descriptor-store
  "XTDB-backed model descriptor storage (Prototype 2).

   Descriptors are stored as entities of type :model/descriptor. This provides
   a self-describing substrate and supports meta/model API endpoints.

   Pattern: storage/schema-evolution-stability"
  (:require [clojure.string :as str]
            [xtdb.api :as xtdb]))

(defn- scope->kw
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (str/trim v)]
                  (when (seq s)
                    (keyword (if (str/starts-with? s ":") (subs s 1) s))))
    :else nil))

(defn descriptor-xt-id
  [scope]
  (str "model|descriptor|" (str (scope->kw scope))))

(defn normalize-descriptor
  "Validate/normalize descriptor shape per mission Section 2.11.1."
  [descriptor]
  (when-not (map? descriptor)
    (throw (ex-info "descriptor must be map" {:descriptor descriptor})))
  (let [scope (scope->kw (:model/scope descriptor))
        version (:schema/version descriptor)
        cert (:schema/certificate descriptor)
        entities (:entities descriptor)
        invariants (:invariants descriptor)]
    (when-not scope
      (throw (ex-info "descriptor missing :model/scope" {:descriptor descriptor})))
    (when-not (and (string? version) (seq (str/trim version)))
      (throw (ex-info "descriptor missing :schema/version" {:descriptor descriptor})))
    (when-not (map? cert)
      (throw (ex-info "descriptor missing :schema/certificate" {:descriptor descriptor})))
    (when-not (map? entities)
      (throw (ex-info "descriptor missing :entities" {:descriptor descriptor})))
    (doseq [[etype edef] entities]
      (when-not (keyword? etype)
        (throw (ex-info "entity type keys must be keywords" {:entity-type etype})))
      (when-not (map? edef)
        (throw (ex-info "entity definition must be map" {:entity-type etype :def edef})))
      (let [req (:required edef)
            strat (:id-strategy edef)]
        (when-not (sequential? req)
          (throw (ex-info "entity definition requires :required seq"
                          {:entity-type etype :required req})))
        (when-not (every? keyword? req)
          (throw (ex-info "entity definition :required must be keywords"
                          {:entity-type etype :required req})))
        (when-not (contains? #{:uuid :custom} strat)
          (throw (ex-info "entity definition :id-strategy must be :uuid or :custom"
                          {:entity-type etype :id-strategy strat})))))
    (when (and invariants (not (sequential? invariants)))
      (throw (ex-info ":invariants must be sequential if present" {:invariants invariants})))
    (assoc descriptor :model/scope scope)))

(defn descriptor->doc
  "Convert normalized descriptor to an XTDB document."
  [descriptor]
  (let [d (normalize-descriptor descriptor)
        scope (:model/scope d)]
    (assoc d
           :xt/id (descriptor-xt-id scope)
           :entity/type :model/descriptor
           :entity/id (descriptor-xt-id scope))))

(defn doc->descriptor
  [doc]
  (dissoc doc :xt/id))

(defn list-descriptors
  [node]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [(pull e [*])]
                      :where [[e :entity/type :model/descriptor]]})
         (map first)
         (sort-by (fn [m] (str (:model/scope m))))
         (vec))))

(defn get-descriptor
  [node scope]
  (let [scope (scope->kw scope)
        db (xtdb/db node)]
    (when scope
      (xtdb/entity db (descriptor-xt-id scope)))))

