(ns futon1a.diag.health
  "Health and diagnostics (stub).")

(defn health-report
  "Return a health summary.

   Inputs:
   - status (keyword)
   - counts (map)
   - last-error (map, optional)
  "
  [{:keys [status counts last-error]}]
  {:status (or status :ok)
   :counts (or counts {})
   :last-error last-error})
