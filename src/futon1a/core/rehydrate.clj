(ns futon1a.core.rehydrate
  "Layer 2 rehydration/integrity gate.

   Rehydration must be all-or-nothing: failures abort startup.")

(defn layer2-error
  "Build a Layer 2 error map." [reason context]
  {:error/layer 2
   :error/status 500
   :error/reason reason
   :error/context context})

(defn- non-empty-vector? [v]
  (and (vector? v) (seq v)))

(defn- normalize-result
  [result]
  (cond
    (nil? result)
    (throw (ex-info "rehydration returned nil"
                    {:error (layer2-error :rehydration-nil {})}))

    (not (map? result))
    (throw (ex-info "rehydration returned non-map"
                    {:error (layer2-error :rehydration-invalid {:result result})}))

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
    (throw (ex-info "load-fn required"
                    {:error (layer2-error :missing-load-fn {})})))
  (let [data (normalize-result (load-fn))
        entities (:entities data)
        relations (:relations data)]
    (when-not (non-empty-vector? entities)
      (throw (ex-info "rehydration produced empty entities"
                      {:error (layer2-error :empty-entities {})})))
    (when (and relations (not (vector? relations)))
      (throw (ex-info "rehydration relations must be vector"
                      {:error (layer2-error :invalid-relations {})})))
    (when validate-fn
      (let [{:keys [ok? errors]} (validate-fn data)]
        (when-not ok?
          (throw (ex-info "integrity validation failed"
                          {:error (layer2-error :integrity-failed
                                               {:errors errors})})))))
    data))
