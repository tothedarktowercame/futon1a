(ns futon1a.api.routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [futon1a.api.routes :as routes]
            [futon1a.model.registry :as registry]))

(use-fixtures :each (fn [f] (registry/clear-registry!) (f)))

(deftest health-endpoint
  (testing "health returns ok"
    (let [resp (routes/health)]
      (is (= 200 (:status resp)))
      (is (= :ok (:status (:body resp)))))))

(deftest health-checks
  (testing "health returns 503 when checks fail"
    (let [resp (routes/health {:checks {:db (fn [] {:ok? false :reason :down})}})]
      (is (= 503 (:status resp)))
      (is (= :degraded (:status (:body resp)))))))

(deftest model-registry-endpoints
  (testing "register and list models"
    (let [resp (routes/register-model {:id :person
                                       :descriptor {:fields [:entity/id :entity/type]
                                                    :required [:entity/id]}})]
      (is (= 200 (:status resp)))
      (is (= :person (get-in resp [:body :id]))))
    (let [resp (routes/list-models)]
      (is (= 200 (:status resp)))
      (is (= [:person] (:models (:body resp)))))))

(deftest ingest-endpoint
  (testing "ingest validates required fields"
    (let [resp (routes/ingest {:entities []})]
      (is (= 400 (:status resp)))))
  (testing "ingest returns counts"
    (let [resp (routes/ingest {:entities [{:entity/id "e1" :entity/type :foo}]
                               :relations []})]
      (is (= 200 (:status resp)))
      (is (= 1 (get-in resp [:body :counts :entities]))))))

(deftest repair-endpoints
  (testing "repair requires entities"
    (let [resp (routes/repair {:dry-run? true})]
      (is (= 400 (:status resp)))))
  (testing "repair returns counts"
    (let [resp (routes/repair {:entities [{:entity/id "e1"}] :dry-run? true})]
      (is (= 200 (:status resp)))
      (is (= 1 (get-in resp [:body :counts :entities])))))
  (testing "verify-repair validates required fields"
    (let [resp (routes/verify-repair {:prev 1 :next 1})]
      (is (= 400 (:status resp)))))
  (testing "verify-repair succeeds"
    (let [resp (routes/verify-repair {:prev 1 :next 1 :label :entities})]
      (is (= 200 (:status resp)))
      (is (= true (get-in resp [:body :ok?]))))))
