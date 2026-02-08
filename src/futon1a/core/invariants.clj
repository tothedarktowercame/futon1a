(ns futon1a.core.invariants
  "Cross-layer invariant helpers (counter-ratchet scaffold)." )

(defn layer2-error
  [reason context]
  {:error/layer 2
   :error/status 500
   :error/reason reason
   :error/context context})

(defn counter-ratchet
  "Ensure that a numeric count does not drop below its previous value.

   Inputs:
   - prev (number)
   - next (number)
   - label (keyword or string)

   Returns {:ok? true} or throws.
  "
  [{:keys [prev next label]}]
  (when (and (number? prev) (number? next) (< next prev))
    (throw (ex-info "counter-ratchet violation"
                    {:error (layer2-error :counter-ratchet
                                          {:label label
                                           :prev prev
                                           :next next})})))
  {:ok? true})
