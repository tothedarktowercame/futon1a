(ns futon1a.model.expansion
  "Determinism vs expansion gate."
  (:require [futon1a.model.validation :as mv]))

(defn expansion-gate!
  "Gate expansion features to an explicit allowlist.

   Inputs:
   - expansion (map with :feature keyword/string, optional)
   - allowed-expansions (set)

   Returns {:ok? true} or throws.
  "
  [{:keys [expansion allowed-expansions]}]
  (when expansion
    (let [feature (:feature expansion)]
      (when-not feature
        (throw (ex-info "expansion feature required"
                        {:error (mv/layer4-error :missing-expansion
                                                 {:expansion expansion})})))
      (when-not (contains? allowed-expansions feature)
        (throw (ex-info "expansion forbidden"
                        {:error (mv/layer4-error :expansion-forbidden
                                                 {:feature feature})})))))
  {:ok? true})
