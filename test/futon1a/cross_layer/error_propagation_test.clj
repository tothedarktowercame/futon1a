(ns futon1a.cross-layer.error-propagation-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]
            [futon1a.core.xtdb :as xt]))

(defrecord OkStore [tx]
  xt/DurableStore
  (submit-tx! [_ _] tx)
  (tx-sync! [_ _] true)
  (entity [_ id] {:xt/id (str id)}))

(defrecord FailingStore []
  xt/DurableStore
  (submit-tx! [_ _] (throw (ex-info "boom" {:cause :submit})))
  (tx-sync! [_ _] true)
  (entity [_ _] nil))

(deftest error-propagation-layer4
  (testing "L4 validation errors propagate to response"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 400 (:status resp)))
      (is (= 4 (get-in resp [:body :error :layer])))
      (is (= :missing-field (get-in resp [:body :error :reason]))))))

(deftest error-propagation-layer3
  (testing "L3 auth errors propagate to response"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "eve"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 403 (:status resp)))
      (is (= 3 (get-in resp [:body :error :layer])))
      (is (= :forbidden (get-in resp [:body :error :reason]))))))

(deftest error-propagation-layer2-before-layer1
  (testing "L2 entity error preempts L1 identity check"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:entity/id "e1"}
                              :required-keys #{:entity/id}
                              :identity {:id "not-a-uuid"}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 500 (:status resp)))
      (is (= 2 (get-in resp [:body :error :layer])))
      (is (= :missing-id (get-in resp [:body :error :reason]))))))

(deftest error-propagation-layer0
  (testing "L0 durability errors propagate to response"
    (let [resp (routes/write {:store (->FailingStore)
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 503 (:status resp)))
      (is (= 0 (get-in resp [:body :error :layer])))
      (is (= :durability-failure (get-in resp [:body :error :reason]))))))
