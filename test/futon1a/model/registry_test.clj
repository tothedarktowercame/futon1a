(ns futon1a.model.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.model.registry :as r]))

(deftest register-and-get-model
  (testing "register and fetch model"
    (let [res (r/register-model! {:id :m1 :descriptor {:fields [:id]}})]
      (is (:ok? res))
      (is (= {:fields [:id]} (r/get-model :m1))))))
