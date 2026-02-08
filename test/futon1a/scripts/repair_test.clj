(ns futon1a.scripts.repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.scripts.repair :as r]))

(deftest repair-entities-basic
  (testing "repair applies repair-fn"
    (let [resp (r/repair-entities! {:entities [{:entity/id "e1"}]
                                    :repair-fn #(assoc % :repaired? true)})]
      (is (:ok? resp))
      (is (= 1 (get-in resp [:counts :repaired])))
      (is (= true (-> resp :repaired first :repaired?))))))

(deftest repair-entities-errors
  (testing "repair collects errors"
    (let [resp (r/repair-entities! {:entities [{:entity/id "e1"}]
                                    :repair-fn (fn [_] (throw (ex-info "boom" {})))})]
      (is (false? (:ok? resp)))
      (is (= 1 (get-in resp [:counts :errors]))))))

(deftest repair-dry-run
  (testing "dry-run returns counts without applying"
    (let [resp (r/repair-entities! {:entities [{:entity/id "e1"}]
                                    :dry-run? true
                                    :repair-fn #(assoc % :repaired? true)})]
      (is (:ok? resp))
      (is (:dry-run? resp))
      (is (= 0 (get-in resp [:counts :repaired]))))))

(deftest verify-repair-uses-counter-ratchet
  (testing "verify-repair passes on equal counts"
    (is (:ok? (r/verify-repair! {:prev 1 :next 1 :label :entities})))))

(deftest verify-repair-rejects-errors
  (testing "verify-repair throws when errors present"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/verify-repair! {:prev 1
                                    :next 1
                                    :label :entities
                                    :errors [{:entity {:entity/id "e1"}}]})))))
