(ns futon1a.invariants.rapid-debugging-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]
            [futon1a.diag.health :as health]))

(deftest rapid-debugging-health-report
  (testing "health report carries checks and last-error for diagnosis"
    (let [report (health/health-report {:checks {:db (fn [] {:ok? false :reason :down})}
                                        :last-error {:layer 2 :reason :missing-id}})]
      (is (= :degraded (:status report)))
      (is (= false (get-in report [:checks :db :ok?])))
      (is (= :down (get-in report [:checks :db :reason])))
      (is (= {:layer 2 :reason :missing-id} (:last-error report))))))

(deftest rapid-debugging-health-endpoint
  (testing "health endpoint returns 503 when degraded"
    (let [resp (routes/health {:checks {:db (fn [] {:ok? false :reason :down})}})]
      (is (= 503 (:status resp)))
      (is (= :degraded (get-in resp [:body :status]))))))
