(ns futon1a.cross-layer.interface-loop-test
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

(deftest interface-loop-error-contexts
  (testing "L4 provides field context"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 400 (:status resp)))
      (is (get-in resp [:body :error :context :field]))))
  (testing "L3 provides penholder context"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "eve"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 403 (:status resp)))
      (is (= "eve" (get-in resp [:body :error :context :penholder])))))
  (testing "L2 provides missing field context"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:entity/id "e1"}
                              :required-keys #{:entity/id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 500 (:status resp)))
      (is (= :entity/type (get-in resp [:body :error :context :field])))))
  (testing "L1 provides id context"
    (let [resp (routes/write {:store (->OkStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :identity {:id "not-a-uuid"}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 409 (:status resp)))
      (is (= "not-a-uuid" (get-in resp [:body :error :context :id])))))
  (testing "L0 provides message context"
    (let [resp (routes/write {:store (->FailingStore)
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 503 (:status resp)))
      (is (string? (get-in resp [:body :error :context :message]))))))
