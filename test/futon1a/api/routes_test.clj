(ns futon1a.api.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]))

(deftest health-endpoint
  (testing "health returns ok"
    (let [resp (routes/health)]
      (is (= 200 (:status resp)))
      (is (= {:status "ok"} (:body resp))))))
