(ns futon1a.core.rehydrate
  "Layer 2 rehydration/integrity gate.

   Rehydration must be all-or-nothing: failures abort startup.")

(defn- non-empty-vector? [v]
  (and (vector? v) (seq v)))

(defn- normalize-result
  [result]
  (cond
    (nil? result)
    (throw (ex-info "rehydration returned nil" {:reason :rehydration-nil}))

    (not (map? result))
    (throw (ex-info "rehydration returned non-map" {:reason :rehydration-invalid}))

    :else
    result))

(defn rehydrate!
  "Run rehydration with integrity gating.

   Inputs:
   - load-fn: () -> {:entities [...] :relations [...]} (or throws)
   - validate-fn: (data) -> {:ok? boolean :errors [...]} (optional)

   Returns the data map when successful; throws on any failure.
  "
  [{:keys [load-fn validate-fn]}]
  (when-not (fn? load-fn)
    (throw (ex-info "load-fn required" {:reason :missing-load-fn})))
  (let [data (normalize-result (load-fn))
        entities (:entities data)
        relations (:relations data)]
    (when-not (non-empty-vector? entities)
      (throw (ex-info "rehydration produced empty entities" {:reason :empty-entities})))
    (when (and relations (not (vector? relations)))
      (throw (ex-info "rehydration relations must be vector"
                      {:reason :invalid-relations})))
    (when validate-fn
      (let [{:keys [ok? errors] :as res} (validate-fn data)]
        (when-not ok?
          (throw (ex-info "integrity validation failed"
                          {:reason :integrity-failed
                           :errors errors
                           :result res})))))
    data))
