(ns futon1a.scripts.repair
  "Repair/backfill tools (stub)."
  (:require [futon1a.core.invariants :as inv]))

(defn repair-entities!
  "Placeholder repair function. Returns {:ok? true}.
   In real use, this will apply repair steps and re-run invariants."
  [_opts]
  {:ok? true})

(defn verify-repair!
  "Verify that repair actions restore invariants.
   Currently invokes counter-ratchet as a placeholder." 
  [{:keys [prev next label]}]
  (inv/counter-ratchet {:prev prev :next next :label label}))
