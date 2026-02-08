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
    ;; Prefer await-tx when we can parse a tx-id, fall back to sync.
    (let [{:keys [tx-id timeout-ms]} (if (map? tx-id) tx-id {:tx-id tx-id})
          tx-id (cond
                  (number? tx-id) tx-id
                  (string? tx-id) (try
                                    (Long/parseLong tx-id)
                                    (catch Exception _ nil))
                  :else nil)
          timeout (when timeout-ms (java.time.Duration/ofMillis timeout-ms))]
      (cond
        (and tx-id timeout) (xtdb/await-tx node {:xtdb.api/tx-id tx-id} timeout)
        tx-id (xtdb/await-tx node {:xtdb.api/tx-id tx-id})
        timeout (xtdb/sync node timeout)
        :else (xtdb/sync node)))))

(defn start-node!
  "Start an XTDB node with the given config map." [config]
  (xtdb/start-node config))

(defn stop-node!
  "Stop an XTDB node." [node]
  (.close node))

(defn xtdb-store
  "Create a DurableStore from an XTDB node." [node]
  (->XtdbStore node))
