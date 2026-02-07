(ns futon1a.test.layer0.durability-gate-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.xtdb :as xt]
            [futon1a.diag.proof-path :as proof]))

(defrecord TestStore [tx]
  xt/DurableStore
  (tx-sync! [_] tx))

(deftest durable-write-produces-proof-path
  (testing "durable write emits complete proof-path"
    (let [store (->TestStore "tx-1")
          result (xt/durable-write!
                  {:store store
                   :actor "tester"
                   :claim {:op :noop}
                   :detail {:layer 0}
                   :write-fn (fn [] :ok)})]
      (is (= "tx-1" (:tx-id result)))
      (is (:ok? (proof/validate-complete-path (:path result)))))))
