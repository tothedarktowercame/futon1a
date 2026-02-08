(ns futon1a.integration.model-descriptor-system-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]
            [futon1a.core.xtdb-node :as xtnode]
            [futon1a.scripts.seed-futon1-descriptors :as seed]
            [xtdb.api :as xtdb]))

(def mem-config
  {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}})

(deftest prototype2-model-descriptor-system
  (testing "seed -> meta/model -> verify -> types"
    (let [node (xtnode/start-node! mem-config)]
      (try
        (let [store (xtnode/xtdb-store node)
              _ (seed/ingest! {:store store
                               :penholder "tester"
                               :allowed-penholders #{"tester"}})
              resp (routes/meta-models {:node node})]
          (is (= 200 (:status resp)))
          (is (= 6 (count (get-in resp [:body :descriptors])))))
        (let [resp (routes/meta-model {:node node :scope :meta-model})]
          (is (= 200 (:status resp)))
          (is (= :meta-model (get-in resp [:body :model/scope]))))
        (let [resp (routes/meta-model-verify {:node node :scope :meta-model})]
          (is (= 200 (:status resp)))
          (is (= true (get-in resp [:body :ok?]))))
        (let [resp (routes/list-types {:node node})]
          (is (= 200 (:status resp)))
          ;; ensure at least the descriptor entity type was auto-registered
          (is (some (fn [t] (= :model/descriptor (:type/id t)))
                    (get-in resp [:body :types]))))
        (finally
          (xtnode/stop-node! node))))))

