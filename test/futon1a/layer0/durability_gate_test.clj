(ns futon1a.layer0.durability-gate-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.xtdb :as xt]
            [futon1a.diag.proof-path :as proof]))

(defrecord TestStore [tx]
  xt/DurableStore
  (submit-tx! [_ _] tx)
  (tx-sync! [_ _] true)
  (entity [_ _] nil))

(deftest durable-write-produces-proof-path
  (testing "durable write emits complete proof-path"
    (let [store (->TestStore "tx-1")
          result (xt/durable-write!
                  {:store store
                   :actor "tester"
                   :claim {:op :noop}
                   :detail {:layer 0}
                   :write-fn (fn [] [{:op :noop}])})]
      (is (= "tx-1" (:tx-id result)))
      (is (:ok? (proof/validate-complete-path (:path result)))))))

(deftest durable-write-requires-write-fn
  (testing "nil write-fn is rejected unless allow-noop?"
    (let [store (->TestStore "tx-3")]
      (is (thrown? clojure.lang.ExceptionInfo
                   (xt/durable-write!
                    {:store store
                     :actor "tester"
                     :claim {:op :noop}
                     :detail {:layer 0}})))
      (is (= "tx-noop"
             (:tx-id (xt/durable-write!
                      {:store store
                       :actor "tester"
                       :claim {:op :noop}
                       :detail {:layer 0}
                       :allow-noop? true})))))))

(deftest durable-write-appends-proof-log
  (testing "durable write can append proof-path EDN"
    (let [store (->TestStore "tx-2")
          tmp (java.io.File/createTempFile "futon1a-proof" ".edn")]
      (.deleteOnExit tmp)
      (xt/durable-write!
       {:store store
        :actor "tester"
        :claim {:op :noop}
        :detail {:layer 0}
        :write-fn (fn [] [{:op :noop}])
        :proof-log-path (.getPath tmp)})
      (is (re-find #":path/id" (slurp tmp))))))

(deftest durable-write-tx-wrapper
  (testing "durable-write-tx! accepts tx-ops"
    (let [store (->TestStore "tx-4")
          result (xt/durable-write-tx!
                  {:store store
                   :actor "tester"
                   :claim {:op :noop}
                   :detail {:layer 0}
                   :tx-ops [{:op :noop}]})]
      (is (= "tx-4" (:tx-id result))))))
