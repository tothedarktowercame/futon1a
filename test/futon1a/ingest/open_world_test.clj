(ns futon1a.ingest.open-world-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.ingest.open-world :as ow]))

(deftest ingest-validates-entities
  (testing "invalid entity is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ow/ingest! {:entities [{:entity/type :foo}] :relations []}))))
  (testing "valid ingest returns counts"
    (let [resp (ow/ingest! {:entities [{:entity/id "e1" :entity/type :foo}]
                            :relations []})]
      (is (= 1 (get-in resp [:counts :entities]))))))
