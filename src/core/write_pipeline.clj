(ns futon1a.core.write-pipeline
  "Stub write pipeline that emits proof-path events (Part II, 2.1).

   This does not perform storage; it only demonstrates the proof-path sequence
   and returns the completed path."
  (:require [futon1a.diag.proof-path :as proof]))

(defn run-write
  "Run a stubbed write pipeline.

   Inputs:
   - actor (string)
   - claim (any)
   - detail (any)

   Returns {:path <proof-path> :result :ok}
  "
  [{:keys [actor claim detail]}]
  (let [path (proof/new-path)
        pid (:path/id path)
        ev #(proof/event {:path/id pid
                          :actor actor
                          :phase %
                          :claim claim
                          :detail detail})
        path (-> path
                 (proof/append-event (ev :clock-in))
                 (proof/append-event (ev :observe))
                 (proof/append-event (ev :propose-claim))
                 (proof/append-event (ev :apply-change))
                 (proof/append-event (ev :verify))
                 (proof/append-event (ev :invariant-check))
                 (proof/append-event (ev :proof-commit))
                 (proof/append-event (ev :clock-out)))]
    {:path path
     :result :ok}))
