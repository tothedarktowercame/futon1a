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
