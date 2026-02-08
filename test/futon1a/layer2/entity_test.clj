(ns futon1a.layer2.entity-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.entity :as ent]))

(deftest validate-entity-requires-keys
  (testing "entity must include id and type"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ent/validate-entity {:entity/type :foo})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ent/validate-entity {:entity/id "e1"})))
    (is (:ok? (ent/validate-entity {:entity/id "e1" :entity/type :foo}))))
  (testing "nil entity rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ent/validate-entity nil)))))

(deftest validate-relation-requires-keys
  (testing "relation must include id/from/to"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ent/validate-relation {:relation/id "r1"})))
    (is (:ok? (ent/validate-relation {:relation/id "r1"
                                      :relation/from "e1"
                                      :relation/to "e2"})))) )

(deftest validate-relations-endpoints
  (testing "relation endpoints must exist"
    (let [entities [{:entity/id "e1" :entity/type :foo}
                    {:entity/id "e2" :entity/type :bar}]
          ok-rel [{:relation/id "r1" :relation/from "e1" :relation/to "e2"}]
          bad-rel [{:relation/id "r2" :relation/from "e1" :relation/to "e3"}]]
      (is (:ok? (ent/validate-relations {:entities entities :relations ok-rel})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ent/validate-relations {:entities entities :relations bad-rel}))))))
