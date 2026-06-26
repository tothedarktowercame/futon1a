(ns futon1a.core.erase-test
  "E-clean-up-substrate-2 part B: the gated GDPR erasure path.

   Logic/gating/shape are unit-tested here with a capturing store; the true
   evict-removes-the-doc semantics are verified live against the running node
   (dry-run + census drop) — XTDB evict can't be faithfully stubbed."
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.auth.penholder :as penholder]
            [futon1a.core.invariants :as inv]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.core.xtdb :as xt]))

;; A store that captures the submitted tx-ops and serves configurable reads.
;; entity returns a non-nil doc by default (so L0 materialization-verify of the
;; audit put passes), overridable per-id via `docs` (for the ratchet test).
(defrecord CaptureStore [captured docs]
  xt/DurableStore
  (submit-tx! [_ tx-ops] (reset! captured tx-ops) "tx-erase")
  (tx-sync! [_ _] true)
  (entity [_ id] (get @docs (str id) {:xt/id (str id)})))

(defn- cap-store [] (->CaptureStore (atom nil) (atom {})))

;; ---- L4 validation --------------------------------------------------------

(deftest erase-rejects-empty-eids
  (testing "no eids → layer-4 error, nothing submitted"
    (let [s (cap-store)]
      (is (thrown? Exception (pipeline/run-erase! {:store s :penholder "joe"
                                                   :eids [] :reason "x"})))
      (is (nil? @(:captured s))))))

(deftest erase-rejects-missing-reason
  (testing "no reason → layer-4 error (GDPR audit needs a reason)"
    (let [s (cap-store)]
      (is (thrown? Exception (pipeline/run-erase! {:store s :penholder "joe"
                                                   :eids ["a"] :reason "  "}))))))

;; ---- L3 authorization (erasure is joe-only) -------------------------------

(deftest erase-gated-to-joe
  (testing "non-joe penholder is forbidden; nil is forbidden"
    (let [s (cap-store)]
      (is (thrown? Exception (pipeline/run-erase! {:store s :penholder "alice"
                                                   :eids ["a"] :reason "r"})))
      (is (thrown? Exception (pipeline/run-erase! {:store s :penholder nil
                                                   :eids ["a"] :reason "r"})))
      (is (nil? @(:captured s)) "forbidden requests submit nothing")))
  (testing "joe is allowed"
    (is (= "joe" (:penholder (penholder/authorize!
                              {:penholder "joe"
                               :allowed-penholders pipeline/erase-allowed-penholders}))))))

;; ---- the submitted tx + the compliance audit record -----------------------

(deftest erase-builds-evicts-plus-nonrevealing-audit
  (testing "one evict op per eid + a single audit put that reveals no content"
    (let [s (cap-store)
          r (pipeline/run-erase! {:store s :penholder "joe"
                                  :eids ["type|entity|:foo" "type|relation|:foo"]
                                  :reason "noise dedup"})
          ops @(:captured s)
          evicts (filter #(= :xtdb.api/evict (first %)) ops)
          puts (filter #(= :xtdb.api/put (first %)) ops)
          audit (->> puts (map second) (filter :erasure/event?) first)]
      (is (:ok? r))
      (is (= 2 (:erased r)))
      (is (= 2 (count evicts)) "one evict per eid")
      (is (= #{"type|entity|:foo" "type|relation|:foo"} (set (map second evicts))))
      (is (some? audit) "an erasure-event audit put is present")
      (is (= 2 (:erasure/eid-count audit)))
      (is (= "joe" (:erasure/by audit)))
      (is (= "noise dedup" (:erasure/reason audit)))
      (is (re-matches #"[0-9a-f]{64}" (:erasure/eid-sha256 audit)) "sha256 receipt of the eids")
      (is (nil? (:erasure/eids audit)) "the audit must NOT retain the raw eids (it is the receipt, not a re-leak)")
      (is (= :erasure-event (:entity/type audit))))))

;; ---- the ratchet now SEES evict (no silent bypass of the count guard) ------

(deftest ratchet-counts-evict-as-a-drop
  (testing "evicting a protected :entity is a drop the ratchet catches without allow-drop"
    (let [store (->CaptureStore (atom nil)
                                (atom {"e1" {:xt/id "e1" :entity/id "e1" :entity/type :thing}}))
          tx-ops [[:xtdb.api/evict "e1"]]]
      (is (thrown? Exception
                   (inv/enforce-counter-ratchet! {:store store :tx-ops tx-ops})))
      (testing "...and is permitted with allow-drop-classes (the deliberate erase)"
        (is (:ok? (inv/enforce-counter-ratchet!
                   {:store store :tx-ops tx-ops :allow-drop-classes #{:entity}})))))))
