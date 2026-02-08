(ns futon1a.layer3.penholder-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.auth.penholder :as ph]))

(deftest authorize-requires-penholder
  (testing "missing penholder is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ph/authorize! {:penholder nil
                                 :allowed-penholders #{"alice"}})))))

(deftest authorize-forbids-unknown
  (testing "unknown penholder is forbidden"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ph/authorize! {:penholder "bob"
                                 :allowed-penholders #{"alice"}})))))

(deftest authorize-accepts-known
  (testing "known penholder passes"
    (let [res (ph/authorize! {:penholder "alice"
                              :allowed-penholders #{"alice" "joe"}})]
      (is (:ok? res))
      (is (= "alice" (:penholder res))))))
