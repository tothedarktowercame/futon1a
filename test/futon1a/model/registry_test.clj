(ns futon1a.model.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [futon1a.model.registry :as r]))

(use-fixtures :each (fn [f] (r/clear-registry!) (f)))

(deftest register-and-get-model
  (testing "register and fetch model"
    (let [res (r/register-model! {:id :m1 :descriptor {:fields [:id]}})]
      (is (:ok? res))
      (is (= {:fields [:id] :required #{:id}} (r/get-model :m1))))))

(deftest registry-rejects-duplicates
  (testing "duplicate registration fails unless override"
    (r/register-model! {:id :m1 :descriptor {:fields [:id]}})
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/register-model! {:id :m1 :descriptor {:fields [:id]}})))
    (is (:ok? (r/register-model! {:id :m1
                                  :descriptor {:fields [:id]}
                                  :override? true})))))
