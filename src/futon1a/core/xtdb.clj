(ns futon1a.core.xtdb
  "Layer 0 durability gate (stub).

   This module defines the durability interface and a proof-path wrapped
   write gate. It does not connect to XTDB yet; it relies on a provided
   store implementation for tx synchronization."
  (:require [futon1a.diag.proof-path :as proof]))

(defprotocol DurableStore
  (submit-tx! [store tx-ops] "Submit tx ops; returns tx-id string.")
  (tx-sync! [store tx-id] "Block until durable commit confirmed for tx-id.")
  (entity [store id] "Read an entity/doc by id from the durable store."))

(defn layer0-error
  "Build a Layer 0 error map." [reason context]
  {:error/layer 0
   :error/status 503
   :error/reason reason
   :error/context context})

(defn- put-docs
  "Extract XTDB put docs from a tx-ops vector."
  [tx-ops]
  (->> tx-ops
       (keep (fn [op]
               (when (and (vector? op)
                          (= :xtdb.api/put (first op))
                          (map? (second op)))
                 (second op))))
       (vec)))

(defn- put-ids
  [tx-ops]
  (->> (put-docs tx-ops)
       (map :xt/id)
       (keep identity)
       (map str)
       (distinct)
       (vec)))

(defn- verify-materialized!
  "Postcondition: every explicit put doc must be readable by :xt/id after tx-sync!.

  If this fails, callers must not treat the write as successful, even if XTDB
  returned a tx-id. This closes the 'accepted but not queryable' failure mode."
  [store tx-id tx-ops]
  (let [docs (put-docs tx-ops)
        missing-xt-id (->> docs (remove :xt/id) (take 3) (vec))]
    (when (seq missing-xt-id)
      (throw (ex-info "put doc missing :xt/id"
                      {:error (layer0-error :missing-xt-id-in-put
                                            {:tx-id tx-id
                                             :sample missing-xt-id})})))
    (let [ids (put-ids tx-ops)
          missing (->> ids
                       (remove (fn [id] (some? (entity store id))))
                       (vec))]
      (when (seq missing)
        (throw (ex-info "durable tx committed but entities not materialized"
                        {:error (layer0-error :postcommit-missing-entities
                                              {:tx-id tx-id
                                               :missing missing
                                               :expected (count ids)
                                               :observed (- (count ids) (count missing))})})))
      {:ok? true
       :counts {:puts (count ids)}})))

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
            tx-id (when tx-ops (submit-tx! store tx-ops))
            tx-id (or tx-id "tx-noop")
            _ (when tx-ops (tx-sync! store tx-id))
            ;; Postcommit materialization check: do not ACK success if the tx
            ;; doesn't yield queryable entities.
            materialized (when tx-ops (verify-materialized! store tx-id tx-ops))
            path (proof/append-event path (proof/event {:path/id pid
                                                        :actor actor
                                                        :phase :verify
                                                        :claim claim
                                                        :detail detail
                                                        :tx-id tx-id
                                                        :evidence (or materialized {:ok? true})}))
            path (proof/append-event path (ev :invariant-check))
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
        (let [edata (ex-data e)
              layer (get-in edata [:error :error/layer])]
          (if layer
            (throw e)
            (throw (ex-info "durable write failed"
                            {:error (layer0-error :durability-failure
                                                  {:message (.getMessage e)})}
                            e))))))))

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
  (tx-sync! [_ _] true)
  ;; Keep stub reads nil by default; durable-write! only verifies :xtdb.api/put ops,
  ;; so callers that use non-XTDB tx-ops in tests remain unaffected.
  (entity [_ _] nil))
