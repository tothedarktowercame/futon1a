(ns futon1a.core.xtdb-node
  "XTDB adapter for Layer 0 durability.

   Provides a DurableStore implementation backed by an XTDB node."
  (:require [xtdb.api :as xtdb]
            [futon1a.core.xtdb :as layer0]))

(defrecord XtdbStore [node]
  layer0/DurableStore
  (submit-tx! [_ tx-ops]
    (let [res (xtdb/submit-tx node tx-ops)
          tx-id (or (:xtdb.api/tx-id res)
                    (:tx-id res)
                    res)]
      (str tx-id)))
  (tx-sync! [_ tx-id]
    ;; Try to parse tx-id as a Long for await-tx; fall back to generic sync.
    ;; StubStore returns "tx-stub" which won't parse — that's fine, sync works.
    (let [parsed (when (string? tx-id)
                   (try (Long/parseLong tx-id) (catch NumberFormatException _ nil)))]
      (if parsed
        (xtdb/await-tx node {:xtdb.api/tx-id parsed})
        (xtdb/sync node))))
  (entity [_ id]
    (xtdb/entity (xtdb/db node) id)))

(defn start-node!
  "Start an XTDB node with the given config map." [config]
  (xtdb/start-node config))

(defn stop-node!
  "Stop an XTDB node." [node]
  (.close node))

(defn xtdb-store
  "Create a DurableStore from an XTDB node." [node]
  (->XtdbStore node))
