(ns futon1a.layer2.rehydrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.rehydrate :as rh]))

(deftest rehydrate-requires-load-fn
  (testing "load-fn is required"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rh/rehydrate! {})))))

(deftest rehydrate-rejects-empty-entities
  (testing "empty entities are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rh/rehydrate!
                  {:load-fn (fn [] {:entities []})})))))

(deftest rehydrate-validates-integrity
  (testing "validate-fn must pass"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rh/rehydrate!
                  {:load-fn (fn [] {:entities [{:id 1}]})
                   :validate-fn (fn [_] {:ok? false :errors [:bad]})})))))

(deftest rehydrate-success
  (testing "returns data on success"
    (let [data (rh/rehydrate!
                {:load-fn (fn [] {:entities [{:id 1}] :relations []})
                 :validate-fn (fn [_] {:ok? true})})]
      (is (= 1 (-> data :entities first :id))))))
