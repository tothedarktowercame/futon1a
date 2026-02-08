(ns futon1a.stress.identity-stress-test
  "Stress tests for concurrent identity validation."
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.identity :as id])
  (:import (java.util.concurrent CountDownLatch)))

(def ^:private thread-count 50)

(defn- run-concurrent
  "Run `f` on `n` threads, all starting simultaneously via a CountDownLatch.
   Returns a vector of results (or Exception wrappers)."
  [n f]
  (let [latch   (CountDownLatch. 1)
        results (atom [])
        threads (mapv (fn [i]
                        (Thread.
                         (fn []
                           (.await latch)
                           (let [r (try (f i) (catch Exception e e))]
                             (swap! results conj r)))))
                      (range n))]
    (doseq [^Thread t threads] (.start t))
    (.countDown latch)
    (doseq [^Thread t threads] (.join t))
    @results))

;; ---------------------------------------------------------------------------
;; (d) Concurrent identity validation with overlapping external-ids
;; ---------------------------------------------------------------------------

(deftest concurrent-identity-validation
  (testing "N threads validate-identity with same external-id, alternating conflict"
    (let [shared-ext-id "shared@example.com"
          results (run-concurrent
                   thread-count
                   (fn [i]
                     (id/validate-identity
                      (if (even? i)
                        ;; No conflict — should succeed
                        {:id          (str (java.util.UUID/randomUUID))
                         :external-id shared-ext-id
                         :source      "stress-test"
                         :existing-by-external nil}
                        ;; Conflict — should throw
                        {:id          (str (java.util.UUID/randomUUID))
                         :external-id shared-ext-id
                         :source      "stress-test"
                         :existing-by-external {:id "existing-entity"}}))))]

      (testing "no unexpected crashes (only ExceptionInfo from conflict)"
        (doseq [r results]
          (when (instance? Exception r)
            (is (instance? clojure.lang.ExceptionInfo r)
                (str "Unexpected exception type: " (class r))))))

      (let [successes  (remove #(instance? Exception %) results)
            exceptions (filter #(instance? Exception %) results)]

        (testing "even-indexed threads succeed"
          (is (= 25 (count successes)))
          (doseq [r successes]
            (is (:ok? r))
            (is (string? (:id r)))
            (is (= shared-ext-id (:external-id r)))))

        (testing "odd-indexed threads get conflict errors"
          (is (= 25 (count exceptions)))
          (doseq [^Exception e exceptions]
            (let [data (ex-data e)]
              (is (= :external-id-conflict (get-in data [:error :error/reason]))))))))))
