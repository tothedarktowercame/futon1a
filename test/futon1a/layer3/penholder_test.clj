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

(deftest authorize-trims-penholder
  (testing "whitespace-only penholder is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ph/authorize! {:penholder "   "
                                 :allowed-penholders #{"alice"}})))))

(deftest authorize-rejects-empty-allowlist
  (testing "empty allowlist rejects all penholders"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ph/authorize! {:penholder "alice"
                                 :allowed-penholders #{}})))))

(deftest authorize-tooling-requires-tooling-id
  (testing "tooling id is required"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ph/authorize-tooling! {:penholder "alice"
                                         :allowed-penholders #{"alice"}
                                         :tooling-id nil
                                         :allowed-tooling #{"cli"}})))))

(deftest authorize-tooling-forbids-unknown
  (testing "tooling id must be in allowlist"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ph/authorize-tooling! {:penholder "alice"
                                         :allowed-penholders #{"alice"}
                                         :tooling-id "unknown"
                                         :allowed-tooling #{"cli"}})))))

(deftest authorize-tooling-accepts-known
  (testing "known tooling passes"
    (let [res (ph/authorize-tooling! {:penholder "alice"
                                      :allowed-penholders #{"alice"}
                                      :tooling-id "cli"
                                      :allowed-tooling #{"cli"}})]
      (is (:ok? res))
      (is (= "cli" (:tooling-id res))))))
