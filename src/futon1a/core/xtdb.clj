(ns futon1a.core.xtdb
  "Layer 0 durability gate (stub).

   This module defines the durability interface and a proof-path wrapped
   write gate. It does not connect to XTDB yet; it relies on a provided
   store implementation for tx synchronization."
  (:require [futon1a.diag.proof-path :as proof]))

(defprotocol DurableStore
  (submit-tx! [store tx-ops] "Submit tx ops; returns tx-id string.")
  (tx-sync! [store tx-id] "Block until durable commit confirmed for tx-id."))

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
   - write-fn (fn [] => tx-ops) or nil if allow-noop?
   - allow-noop? (boolean, optional) => allow nil write-fn
   - proof-log-path (string, optional) => append proof-path EDN line

   Returns {:tx-id <string> :path <proof-path>}"
  [{:keys [store actor claim detail write-fn allow-noop? proof-log-path]}]
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
      (when (and (nil? write-fn) (not allow-noop?))
        (throw (ex-info "write-fn required" {:reason :missing-write-fn})))
      (let [tx-ops (when write-fn (write-fn))
            _ (when (and (nil? tx-ops) (not allow-noop?) write-fn)
                (throw (ex-info "write-fn returned nil tx-ops" {:reason :missing-tx-ops})))
            path (proof/append-event path (ev :verify))
            path (proof/append-event path (ev :invariant-check))
            tx-id (when tx-ops (submit-tx! store tx-ops))
            tx-id (or tx-id "tx-noop")
            _ (when tx-ops (tx-sync! store tx-id))
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
      (catch Exception e
        (throw (ex-info "durable write failed"
                        {:error (layer0-error :durability-failure
                                              {:message (.getMessage e)})}
                        e))))))

(defn durable-write-tx!
  "Convenience wrapper for a direct tx-ops vector.

   Inputs:
   - store, actor, claim, detail as in durable-write!
   - tx-ops (vector)
   - proof-log-path (optional)
  "
  [{:keys [store actor claim detail tx-ops proof-log-path]}]
  (when-not (vector? tx-ops)
    (throw (ex-info "tx-ops must be a vector" {:tx-ops tx-ops})))
  (durable-write! {:store store
                   :actor actor
                   :claim claim
                   :detail detail
                   :write-fn (fn [] tx-ops)
                   :proof-log-path proof-log-path}))

(defrecord StubStore []
  DurableStore
  (submit-tx! [_ _] "tx-stub")
  (tx-sync! [_ _] true))
