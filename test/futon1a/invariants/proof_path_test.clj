(ns futon1a.invariants.proof-path-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.diag.proof-path :as proof]
            [futon1a.core.xtdb :as xt]))

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

(deftest proof-path-schema-validation
  (testing "event schema validation"
    (let [p (proof/new-path)
          ok (proof/event {:path/id (:path/id p)
                           :actor "tester"
                           :phase :clock-in})
          bad {:path/id nil :actor 42 :phase :nope}]
      (is (:ok? (proof/validate-event ok)))
      (is (false? (:ok? (proof/validate-event bad)))))))

(deftest proof-path-validation
  (testing "full path validation"
    (let [p (proof/new-path)
          ev1 (proof/event {:path/id (:path/id p)
                            :actor "tester"
                            :phase :clock-in})
          ev2 (proof/event {:path/id (:path/id p)
                            :actor "tester"
                            :phase :observe})
          ok-path (-> p (proof/append-event ev1) (proof/append-event ev2))
          bad-path (assoc p :events [ev2 ev1])]
      (is (:ok? (proof/validate-path ok-path)))
      (is (false? (:ok? (proof/validate-path bad-path)))))))

(deftest proof-path-complete-validation
  (testing "complete path requires full phase sequence"
    (let [{:keys [path]} (xt/durable-write!
                          {:store (xt/->StubStore)
                           :actor "tester"
                           :claim {:op :noop}
                           :detail {}
                           :write-fn (fn [] [{:op :noop}])})]
      (is (:ok? (proof/validate-complete-path path)))))
  (testing "incomplete path is rejected"
    (let [p (proof/new-path)
          ev1 (proof/event {:path/id (:path/id p)
                            :actor "tester"
                            :phase :clock-in})
          path (proof/append-event p ev1)
          res (proof/validate-complete-path path)]
      (is (false? (:ok? res))))))

(deftest proof-path-edn-append
  (testing "append-edn! writes a line"
    (let [p (proof/new-path)
          ev1 (proof/event {:path/id (:path/id p)
                            :actor "tester"
                            :phase :clock-in})
          path (proof/append-event p ev1)
          tmp (java.io.File/createTempFile "futon1a-proof" ".edn")]
      (.deleteOnExit tmp)
      (proof/append-edn! path (.getPath tmp))
      (let [contents (slurp tmp)]
        (is (re-find #":path/id" contents))))))
