(ns futon1a.test.invariants.proof-path-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.diag.proof-path :as proof]))

(deftest proof-path-ordering
  (testing "proof-path phases enforce ordering"
    (let [p (proof/new-path)
          ev1 (proof/event {:path/id (:path/id p)
                            :actor "tester"
                            :phase :clock-in})
          ev2 (proof/event {:path/id (:path/id p)
                            :actor "tester"
                            :phase :observe})]
      (is (= [:clock-in]
             (proof/event-seq (proof/append-event p ev1))))
      (is (= [:clock-in :observe]
             (proof/event-seq (-> p
                                   (proof/append-event ev1)
                                   (proof/append-event ev2))))))))
