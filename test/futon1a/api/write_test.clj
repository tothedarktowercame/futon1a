(ns futon1a.api.write-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]
            [futon1a.core.xtdb :as xt]))

(defrecord TestStore [tx]
  xt/DurableStore
  (submit-tx! [_ _] tx)
  (tx-sync! [_ _] true))

(deftest write-requires-penholder
  (testing "missing penholder returns 403"
    (let [resp (routes/write {:store (->TestStore "tx")
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :tx-ops [{:op :noop}]})]
      (is (= 403 (:status resp))))))

(deftest write-validates-model
  (testing "missing required model field returns 400"
    (let [resp (routes/write {:store (->TestStore "tx")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {}
                              :required-keys #{:id}
                              :tx-ops [{:op :noop}]})]
      (is (= 400 (:status resp))))))

(deftest write-success
  (testing "valid write returns tx-id and path/id"
    (let [resp (routes/write {:store (->TestStore "tx-ok")
                              :penholder "alice"
                              :allowed-penholders #{"alice"}
                              :model {:id 1}
                              :required-keys #{:id}
                              :tx-ops [{:op :noop}]})]
      (is (= 200 (:status resp)))
      (is (= "tx-ok" (get-in resp [:body :tx-id])))
      (is (string? (get-in resp [:body :path/id]))))))
