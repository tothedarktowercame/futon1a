(ns futon1a.integration.xtdb-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.core.xtdb :as layer0]
            [futon1a.core.xtdb-node :as xtnode]
            [xtdb.api :as xtdb]))

(def xtdb-config
  {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}})

(deftest durable-write-xtdb
  (testing "durable-write-tx! persists doc in XTDB"
    (let [node (xtnode/start-node! xtdb-config)]
      (try
        (let [store (xtnode/xtdb-store node)
              tx-ops [[::xtdb/put {:xt/id "e1" :entity/type :foo}]]
              result (layer0/durable-write-tx!
                      {:store store
                       :actor "tester"
                       :claim {:op :put}
                       :detail {:layer :integration}
                       :tx-ops tx-ops})
              db (xtdb/db node)
              ent (xtdb/entity db "e1")]
          (is (:tx-id result))
          (is (layer0/tx-sync! store {:tx-id (:tx-id result) :timeout-ms 1000}))
          (is (= :foo (:entity/type ent))))
        (finally
          (xtnode/stop-node! node))))))
