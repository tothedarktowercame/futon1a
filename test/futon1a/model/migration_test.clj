(ns futon1a.model.migration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [futon1a.model.migration :as mig]))

(use-fixtures :each (fn [f] (mig/clear-migrations!) (f)))

(deftest register-migration-requires-fn
  (testing "register rejects missing migrate-fn"
    (is (thrown? clojure.lang.ExceptionInfo
                 (mig/register-migration! {:model-id :m1 :from 1 :to 2})))))

(deftest migrate-requires-registration
  (testing "missing migration is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (mig/migrate! {:model-id :m1 :from 1 :to 2 :payload {:x 1}})))))

(deftest migrate-applies-fn
  (testing "registered migration transforms payload"
    (mig/register-migration! {:model-id :m1
                              :from 1
                              :to 2
                              :migrate-fn #(assoc % :migrated? true)})
    (let [resp (mig/migrate! {:model-id :m1 :from 1 :to 2 :payload {:x 1}})]
      (is (:ok? resp))
      (is (= true (get-in resp [:payload :migrated?]))))))
