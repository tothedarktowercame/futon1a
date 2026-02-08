(ns futon1a.diag.health-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.diag.health :as h]))

(deftest health-report-shape
  (testing "health report includes status and counts"
    (let [resp (h/health-report {:status :ok :counts {:entities 1}})]
      (is (= :ok (:status resp)))
      (is (= {:entities 1} (:counts resp))))))

(deftest health-report-checks
  (testing "health report derives status from checks"
    (let [resp (h/health-report {:checks {:db (fn [] {:ok? true})
                                          :mirror (fn [] {:ok? false :reason :stale})}})]
      (is (= :degraded (:status resp)))
      (is (false? (get-in resp [:checks :mirror :ok?]))))))
