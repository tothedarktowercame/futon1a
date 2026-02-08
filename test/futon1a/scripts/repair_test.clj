(ns futon1a.scripts.repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.scripts.repair :as r]))

(deftest repair-entities-placeholder
  (testing "repair returns ok"
    (is (:ok? (r/repair-entities! {})))))

(deftest verify-repair-uses-counter-ratchet
  (testing "verify-repair passes on equal counts"
    (is (:ok? (r/verify-repair! {:prev 1 :next 1 :label :entities})))) )
