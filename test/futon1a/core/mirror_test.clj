(ns futon1a.core.mirror-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.mirror :as m]))

(deftest mirror-entity-requires-id
  (testing "entity must have id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (m/mirror-entity! (m/->StubMirrorStore) {:entity/type :foo})))
    (is (= {:entity/id "e1" :entity/type :foo}
           (m/mirror-entity! (m/->StubMirrorStore) {:entity/id "e1" :entity/type :foo})))))

(deftest mirror-relation-requires-id
  (testing "relation must have id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (m/mirror-relation! (m/->StubMirrorStore) {:relation/from "e1"})))
    (is (= {:relation/id "r1" :relation/from "e1" :relation/to "e2"}
           (m/mirror-relation! (m/->StubMirrorStore)
                                {:relation/id "r1" :relation/from "e1" :relation/to "e2"})))))

(deftest in-memory-mirror-stores
  (testing "in-memory store records entities and relations"
    (let [store (m/in-memory-store)]
      (m/mirror-entity! store {:entity/id "e1" :entity/type :foo})
      (m/mirror-relation! store {:relation/id "r1" :relation/from "e1" :relation/to "e2"})
      (is (= {:entity/id "e1" :entity/type :foo}
             (get-in (m/mirror-snapshot store) [:entities "e1"])))
      (is (= {:relation/id "r1" :relation/from "e1" :relation/to "e2"}
             (get-in (m/mirror-snapshot store) [:relations "r1"]))))))

(deftest mirror-batch-rejects-duplicates
  (testing "batch rejects duplicate ids"
    (let [store (m/in-memory-store)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (m/mirror-batch! store {:entities [{:entity/id "e1" :entity/type :foo}
                                                      {:entity/id "e1" :entity/type :foo}]
                                           :relations []})))) )
  (testing "batch returns counts"
    (let [store (m/in-memory-store)
          resp (m/mirror-batch! store {:entities [{:entity/id "e1" :entity/type :foo}]
                                       :relations [{:relation/id "r1" :relation/from "e1" :relation/to "e2"}]})]
      (is (= 1 (get-in resp [:counts :entities])))
      (is (= 1 (get-in resp [:counts :relations]))))))

(deftest in-memory-mirror-rejects-mismatch
  (testing "re-mirroring with mismatched payload fails"
    (let [store (m/in-memory-store)]
      (m/mirror-entity! store {:entity/id "e1" :entity/type :foo})
      (is (thrown? clojure.lang.ExceptionInfo
                   (m/mirror-entity! store {:entity/id "e1" :entity/type :bar}))))))
