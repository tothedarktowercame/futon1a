(ns futon1a.layer4.expansion-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.model.expansion :as exp]))

(deftest expansion-gate-allows-none
  (testing "no expansion passes"
    (is (:ok? (exp/expansion-gate! {:expansion nil
                                    :allowed-expansions #{}})))))

(deftest expansion-gate-requires-feature
  (testing "missing feature is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (exp/expansion-gate! {:expansion {:flag true}
                                       :allowed-expansions #{:beta}})))))

(deftest expansion-gate-requires-allowlist
  (testing "unknown feature is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (exp/expansion-gate! {:expansion {:feature :beta}
                                       :allowed-expansions #{:alpha}})))))

(deftest expansion-gate-accepts-allowed
  (testing "allowed feature passes"
    (is (:ok? (exp/expansion-gate! {:expansion {:feature :beta}
                                    :allowed-expansions #{:beta}})))))
