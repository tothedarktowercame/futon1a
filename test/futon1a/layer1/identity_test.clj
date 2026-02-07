(ns futon1a.layer1.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.identity :as id]))

(deftest validate-identity-uuid
  (testing "reject non-UUID ids"
    (is (thrown? clojure.lang.ExceptionInfo
                 (id/validate-identity {:id "not-a-uuid"}))))
  (testing "accepts UUID ids"
    (let [uuid "123e4567-e89b-12d3-a456-426614174000"
          res (id/validate-identity {:id uuid})]
      (is (:ok? res))
      (is (= uuid (:id res))))))

(deftest validate-identity-external-id
  (testing "external-id uniqueness"
    (is (thrown? clojure.lang.ExceptionInfo
                 (id/validate-identity {:external-id "x"
                                        :existing-by-external {:id "a"}}))))
  (testing "normalizes external-id"
    (let [res (id/validate-identity {:external-id "  ext-1  "})]
      (is (= "ext-1" (:external-id res))))))
