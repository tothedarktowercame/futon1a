(ns futon1a.cross-layer.pipeline-order-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]
            [futon1a.core.xtdb :as xt]))

(defrecord TestStore [tx]
  xt/DurableStore
  (submit-tx! [_ _] tx)
  (tx-sync! [_ _] true)
  (entity [_ id] {:xt/id (str id)}))

(deftest pipeline-orders-layer4-before-layer3
  (testing "L4 model validation wins over L3 auth"
    (let [resp (routes/write {:store (->TestStore "tx")
                              :penholder nil
                              :allowed-penholders #{"alice"}
                              :model {}
                              :required-keys #{:id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 400 (:status resp))))))

(deftest pipeline-identity-error-propagates
  (testing "L1 identity errors surface as 409"
    (let [resp (routes/write {:store (->TestStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :identity {:id "not-a-uuid"}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 409 (:status resp))))))

(deftest pipeline-entity-error-propagates
  (testing "L2 entity errors surface as 500"
    (let [resp (routes/write {:store (->TestStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:entity/id "e1"}
                              :required-keys #{:entity/id}
                              :tx-ops [[:xtdb.api/put {:xt/id "noop"}]]})]
      (is (= 500 (:status resp))))))
