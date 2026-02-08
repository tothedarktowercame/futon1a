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
