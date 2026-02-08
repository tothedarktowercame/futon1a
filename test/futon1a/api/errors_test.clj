(ns futon1a.api.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.errors :as err]))

(deftest error->response-shape
  (testing "maps error to status and body"
    (let [resp (err/error->response {:error {:error/layer 2
                                            :error/status 500
                                            :error/reason :oops
                                            :error/context {:x 1}}})]
      (is (= 500 (:status resp)))
      (is (= {:error {:layer 2 :reason :oops :context {:x 1}}}
             (:body resp))))))
