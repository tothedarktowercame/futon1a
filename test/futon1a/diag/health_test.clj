(ns futon1a.diag.health-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.diag.health :as h]))

(deftest health-report-shape
  (testing "health report includes status and counts"
    (let [resp (h/health-report {:status :ok :counts {:entities 1}})]
      (is (= :ok (:status resp)))
      (is (= {:entities 1} (:counts resp))))))
