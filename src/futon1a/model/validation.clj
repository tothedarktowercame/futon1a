(ns futon1a.model.validation
  "Layer 4 model validation gate.")

(defn layer4-error
  [reason context]
  {:error/layer 4
   :error/status 400
   :error/reason reason
   :error/context context})

(defn validate-model!
  "Validate a model payload.

   Inputs:
   - model (map)
   - required-keys (set of keywords)

   Returns {:ok? true} or throws.
  "
  [{:keys [model required-keys]}]
  (doseq [k required-keys]
    (when-not (contains? model k)
      (throw (ex-info "model validation failed"
                      {:error (layer4-error :missing-field {:field k})}))))
  {:ok? true})
