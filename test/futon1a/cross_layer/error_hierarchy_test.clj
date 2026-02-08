(ns futon1a.cross-layer.error-hierarchy-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.xtdb :as xt]
            [futon1a.core.identity :as id]
            [futon1a.core.rehydrate :as rh]
            [futon1a.core.entity :as ent]
            [futon1a.auth.penholder :as ph]
            [futon1a.model.validation :as mv]))

(deftest error-layer-hierarchy
  (testing "Layer 0 errors surface as layer 0, status 503"
    (try
      (xt/durable-write!
       {:store (xt/->StubStore)
        :actor "tester"
        :claim {:op :noop}
        :detail {:layer 0}})
      (is false "expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= 0 (get-in (ex-data e) [:error :error/layer])))
        (is (= 503 (get-in (ex-data e) [:error :error/status]))))))

  (testing "Layer 1 errors surface as layer 1, status 409"
    (try
      (id/validate-identity {:id "not-a-uuid"})
      (is false "expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= 1 (get-in (ex-data e) [:error :error/layer])))
        (is (= 409 (get-in (ex-data e) [:error :error/status]))))))

  (testing "Layer 2 rehydration errors surface as layer 2, status 500"
    (try
      (rh/rehydrate! {:load-fn (fn [] {:entities []})})
      (is false "expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= 2 (get-in (ex-data e) [:error :error/layer])))
        (is (= 500 (get-in (ex-data e) [:error :error/status]))))))

  (testing "Layer 2 entity errors surface as layer 2, status 500"
    (try
      (ent/validate-relations {:entities [{:entity/id "e1" :entity/type :foo}]
                               :relations [{:relation/id "r1"
                                            :relation/from "e1"
                                            :relation/to "e2"}]})
      (is false "expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= 2 (get-in (ex-data e) [:error :error/layer])))
        (is (= 500 (get-in (ex-data e) [:error :error/status]))))))

  (testing "Layer 3 errors surface as layer 3, status 403"
    (try
      (ph/authorize! {:penholder "eve" :allowed-penholders #{"alice"}})
      (is false "expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= 3 (get-in (ex-data e) [:error :error/layer])))
        (is (= 403 (get-in (ex-data e) [:error :error/status]))))))

  (testing "Layer 4 errors surface as layer 4, status 400"
    (try
      (mv/validate-model! {:model {} :required-keys #{:id}})
      (is false "expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= 4 (get-in (ex-data e) [:error :error/layer])))
        (is (= 400 (get-in (ex-data e) [:error :error/status])))))))
