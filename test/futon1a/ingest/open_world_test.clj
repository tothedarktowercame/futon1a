(ns futon1a.ingest.open-world-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.ingest.open-world :as ow]
            [futon1a.model.registry :as registry]))

(deftest ingest-validates-entities
  (testing "invalid entity is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ow/ingest! {:entities [{:entity/type :foo}] :relations []}))))
  (testing "valid ingest returns counts"
    (let [resp (ow/ingest! {:entities [{:entity/id "e1" :entity/type :foo}]
                            :relations []})]
      (is (= 1 (get-in resp [:counts :entities]))))))

(deftest ingest-validates-models
  (testing "missing model descriptor can be required"
    (registry/clear-registry!)
    (is (thrown? clojure.lang.ExceptionInfo
                 (ow/ingest! {:entities [{:entity/id "e1" :entity/type :unknown}]
                             :relations []
                             :require-model? true}))))
  (testing "model validation uses registry required fields"
    (registry/clear-registry!)
    (registry/register-model! {:id :person
                               :descriptor {:fields [:entity/id :entity/type :person/name]
                                            :required [:person/name]}})
    (is (thrown? clojure.lang.ExceptionInfo
                 (ow/ingest! {:entities [{:entity/id "e1" :entity/type :person}]
                             :relations []
                             :require-model? true})))
    (let [resp (ow/ingest! {:entities [{:entity/id "e1"
                                        :entity/type :person
                                        :person/name "Ada"}]
                            :relations []
                            :require-model? true})]
      (is (= 1 (get-in resp [:counts :models-validated]))))))
