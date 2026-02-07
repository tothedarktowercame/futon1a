(ns futon1a.core.xtdb
  "Layer 0 durability gate (stub).

   This module defines the durability interface and a proof-path wrapped
   write gate. It does not connect to XTDB yet; it relies on a provided
   store implementation for tx synchronization."
  (:require [futon1a.diag.proof-path :as proof]))

(defprotocol DurableStore
  (tx-sync! [store] "Block until durable commit confirmed; returns tx-id string."))

(defn layer0-error
  "Build a Layer 0 error map." [reason context]
  {:error/layer 0
   :error/status 503
   :error/reason reason
   :error/context context})

(defn durable-write!
  "Run a durability-gated write with proof-path logging.

   Inputs:
   - store (implements DurableStore)
   - actor (string)
   - claim (any)
   - detail (any)
   - write-fn (fn [] => side effects; should throw on failure)
   - proof-log-path (string, optional) => append proof-path EDN line

   Returns {:tx-id <string> :path <proof-path>}"
  [{:keys [store actor claim detail write-fn proof-log-path]}]
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
                 (proof/append-event (ev :apply-change)))]
    (try
      (when write-fn (write-fn))
      (let [path (proof/append-event path (ev :verify))
            path (proof/append-event path (ev :invariant-check))
            tx-id (tx-sync! store)
            path (proof/append-event path (proof/event {:path/id pid
                                                        :actor actor
                                                        :phase :proof-commit
                                                        :tx-id tx-id
                                                        :claim claim
                                                        :detail detail}))
            path (proof/append-event path (ev :clock-out))
            _ (when-not (:ok? (proof/validate-complete-path path))
                (throw (ex-info "proof-path invalid" {:path path})))
            _ (when proof-log-path
                (proof/append-edn! path proof-log-path))]
        {:tx-id tx-id
         :path path})
      (catch Throwable t
        (throw (ex-info "durable write failed"
                        {:error (layer0-error :durability-failure
                                              {:message (.getMessage t)})}
                        t))))))

(defrecord StubStore []
  DurableStore
  (tx-sync! [_] "tx-stub"))
