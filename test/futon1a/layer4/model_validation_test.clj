(ns futon1a.layer4.model-validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.model.validation :as v]))

(deftest validate-model-required-keys
  (testing "missing required fields are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/validate-model! {:model {} :required-keys #{:id}}))))
  (testing "valid model passes"
    (is (:ok? (v/validate-model! {:model {:id 1}
                                  :required-keys #{:id}})))))

(deftest validate-model-nil-or-non-map
  (testing "nil model rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/validate-model! {:model nil :required-keys #{:id}}))))
  (testing "non-map model rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/validate-model! {:model "bad" :required-keys #{:id}})))))
