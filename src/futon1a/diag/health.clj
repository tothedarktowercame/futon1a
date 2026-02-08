(ns futon1a.diag.health
  "Health and diagnostics.")

(defn- run-checks
  [checks]
  (into {}
        (for [[id check-fn] checks]
          (let [result (try
                         (let [resp (check-fn)]
                           (if (map? resp)
                             resp
                             {:ok? (boolean resp)}))
                         (catch Exception e
                           {:ok? false
                            :error (.getMessage e)}))]
            [id result]))))

(defn health-report
  "Return a health summary.

   Inputs:
   - status (keyword)
   - counts (map)
   - last-error (map, optional)
   - checks (map of keyword -> fn, optional)
  "
  [{:keys [status counts last-error checks]}]
  (let [check-results (when (seq checks) (run-checks checks))
        derived-status (cond
                         status status
                         (empty? check-results) :ok
                         (every? (fn [[_ v]] (:ok? v)) check-results) :ok
                         :else :degraded)]
    {:status derived-status
     :counts (or counts {})
     :checks check-results
     :last-error last-error}))
