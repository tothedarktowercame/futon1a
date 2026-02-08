(ns futon1a.layer0.durability-gate-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.xtdb :as xt]
            [futon1a.diag.proof-path :as proof]))

(defrecord TestStore [tx]
  xt/DurableStore
  (submit-tx! [_ _] tx)
  (tx-sync! [_ _] true)
  (entity [_ id] {:xt/id (str id)}))

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
                   :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= "tx-4" (:tx-id result))))))

(defrecord MaterializingStore [tx docs]
  xt/DurableStore
  (submit-tx! [_ tx-ops]
    ;; Store only explicit XTDB put docs keyed by :xt/id.
    (doseq [op tx-ops]
      (when (and (vector? op) (= :xtdb.api/put (first op)) (map? (second op)))
        (let [doc (second op)
              id (:xt/id doc)]
          (when id
            (swap! docs assoc (str id) doc)))))
    tx)
  (tx-sync! [_ _] true)
  (entity [_ id] (get @docs (str id))))

(deftest durable-write-verifies-materialization
  (testing "writes that do not materialize put docs are rejected at Layer 0"
    (let [docs (atom {})
          store (->MaterializingStore "tx-5" docs)]
      ;; Good: entity exists after submit.
      (let [res (xt/durable-write-tx!
                 {:store store
                  :actor "tester"
                  :claim {:op :put}
                  :detail {:layer 0}
                  :tx-ops [[:xtdb.api/put {:xt/id "e1" :entity/type :foo}]]})]
        (is (= "tx-5" (:tx-id res)))
        (is (get @docs "e1")))

      ;; Bad: simulate a store that *acks* but doesn't materialize.
      (let [store2 (reify xt/DurableStore
                     (submit-tx! [_ _] "tx-6")
                     (tx-sync! [_ _] true)
                     (entity [_ _] nil))]
        (try
          (xt/durable-write-tx!
           {:store store2
            :actor "tester"
            :claim {:op :put}
            :detail {:layer 0}
            :tx-ops [[:xtdb.api/put {:xt/id "e2" :entity/type :foo}]]})
          (is false "expected exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= 0 (get-in (ex-data e) [:error :error/layer])))
            (is (= :postcommit-missing-entities (get-in (ex-data e) [:error :error/reason])))))))))
