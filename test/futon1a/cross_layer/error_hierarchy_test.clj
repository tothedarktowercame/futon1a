(ns futon1a.cross-layer.error-hierarchy-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.xtdb :as xt]
            [futon1a.core.identity :as id]
            [futon1a.core.rehydrate :as rh]
            [futon1a.core.entity :as ent]))

(deftest error-layer-hierarchy
  (testing "Layer 0 errors surface as 503"
    (is (thrown? clojure.lang.ExceptionInfo
                 (xt/durable-write!
                  {:store (xt/->StubStore)
                   :actor "tester"
                   :claim {:op :noop}
                   :detail {:layer 0}}))))
  (testing "Layer 1 errors surface as 409"
    (try
      (id/validate-identity {:id "not-a-uuid"})
      (catch clojure.lang.ExceptionInfo e
        (is (= 1 (get-in (ex-data e) [:error :error/layer])))
        (is (= 409 (get-in (ex-data e) [:error :error/status]))))))
  (testing "Layer 2 errors surface as 500"
    (try
      (rh/rehydrate! {:load-fn (fn [] {:entities []})})
      (catch clojure.lang.ExceptionInfo e
        (is (= 2 (get-in (ex-data e) [:error :error/layer])))
        (is (= 500 (get-in (ex-data e) [:error :error/status]))))))
  (testing "Layer 2 entity errors surface as 500"
    (try
      (ent/validate-relations {:entities [{:entity/id "e1" :entity/type :foo}]
                               :relations [{:relation/id "r1"
                                            :relation/from "e1"
                                            :relation/to "e2"}]})
      (catch clojure.lang.ExceptionInfo e
        (is (= 2 (get-in (ex-data e) [:error :error/layer])))
        (is (= 500 (get-in (ex-data e) [:error :error/status])))))))
