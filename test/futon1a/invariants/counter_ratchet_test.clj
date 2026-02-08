(ns futon1a.invariants.counter-ratchet-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.invariants :as inv]))

(deftest counter-ratchet-accepts-increase
  (testing "increasing counts pass"
    (is (:ok? (inv/counter-ratchet {:prev 10 :next 10 :label :entities})))
    (is (:ok? (inv/counter-ratchet {:prev 10 :next 11 :label :entities})))) )

(deftest counter-ratchet-rejects-drop
  (testing "count drops are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (inv/counter-ratchet {:prev 10 :next 9 :label :entities})))) )
