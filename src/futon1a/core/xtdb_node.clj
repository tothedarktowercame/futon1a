(ns futon1a.core.xtdb-node
  "XTDB adapter for Layer 0 durability.

   Provides a DurableStore implementation backed by an XTDB node."
  (:require [xtdb.api :as xtdb]
            [futon1a.core.xtdb :as layer0]))

(defrecord XtdbStore [node]
  layer0/DurableStore
  (submit-tx! [_ tx-ops]
    (let [res (xtdb/submit-tx node tx-ops)]
      (or (:xtdb.api/tx-id res)
          (:tx-id res)
          (str res))))
  (tx-sync! [_ _tx-id]
    ;; XTDB v1 does not accept tx-id in sync; this waits for latest tx.
    (xtdb/sync node)))

(defn start-node!
  "Start an XTDB node with the given config map." [config]
  (xtdb/start-node config))

(defn stop-node!
  "Stop an XTDB node." [node]
  (.close node))

(defn xtdb-store
  "Create a DurableStore from an XTDB node." [node]
  (->XtdbStore node))
