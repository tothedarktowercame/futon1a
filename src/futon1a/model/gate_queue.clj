(ns futon1a.model.gate-queue
  "Inspectable record of L4 write-gate id-contract decisions (E-futon1a-archivist,
   slice 1). Non-canonical writes must be a *visible* gate failure, not a silent
   drop discovered weeks later — so every rejected or queued write is recorded
   here and surfaced at GET /meta/model/queue.

   In-memory (:prototyping-forward): a durable, restart-surviving queue is the
   follow-on once the gate's behaviour is ratified live. Scope guard: this only
   records gate decisions; it does not itself gate or write.")

(defonce ^:private log (atom []))

(defn record!
  "Record one gate decision.

   - entity-id   the offending :entity/id
   - entity-type the :entity/type whose descriptor carried the id-contract
   - disposition :rejected | :queued
   - reason      keyword (e.g. :non-canonical-id, :alias-pending-migration)
   - expected    the canonical :id-pattern the id was checked against"
  [{:keys [entity-id entity-type disposition reason expected]}]
  (swap! log conj {:entity/id entity-id
                   :entity/type entity-type
                   :gate/disposition disposition
                   :gate/reason reason
                   :gate/expected expected
                   :gate/at (System/currentTimeMillis)})
  nil)

(defn snapshot
  "All recorded gate decisions, oldest first."
  []
  @log)

(defn clear!
  "Reset the log (used by tests and after a queue is drained by migration)."
  []
  (reset! log []))
