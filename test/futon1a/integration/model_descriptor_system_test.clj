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
        ;; Inject one legacy descriptor doc (missing certificate) to ensure the
        ;; repair step upgrades migrated stores.
        (xtdb/await-tx node (xtdb/submit-tx node [[:xtdb.api/put {:xt/id #uuid "2704308f-c6df-460f-bfa1-658023cc23e2"
                                                                  :entity/type :model/descriptor
                                                                  :entity/name "model/descriptor/media"
                                                                  :entity/external-id "0.1.2"}]]))
        (let [store (xtnode/xtdb-store node)]
          (seed/ingest! {:store store
                         :penholder "tester"
                         :allowed-penholders #{"tester"}})
          (let [resp (routes/meta-models {:node node})]
            (is (= 200 (:status resp)))
            (is (<= 6 (count (get-in resp [:body :descriptors])))))
          (let [resp (routes/meta-model {:node node :scope :meta-model})]
            (is (= 200 (:status resp)))
            (is (= :meta-model (get-in resp [:body :model/scope]))))
          (let [resp (routes/meta-model-verify {:node node :scope :meta-model})]
            (is (= 200 (:status resp)))
            ;; The legacy descriptor missing certificate should make this fail pre-repair.
            (is (= false (get-in resp [:body :ok?]))))
          (require 'futon1a.scripts.repair-legacy-descriptors)
          (let [repair! (resolve 'futon1a.scripts.repair-legacy-descriptors/repair!)]
            (repair! {:node node
                      :store store
                      :penholder "tester"
                      :allowed-penholders #{"tester"}}))
          (let [resp (routes/meta-model-verify {:node node :scope :meta-model})]
            (is (= 200 (:status resp)))
            (is (= true (get-in resp [:body :ok?]))))
          (let [resp (routes/list-types {:node node})]
            (is (= 200 (:status resp)))
            ;; ensure at least the descriptor entity type was auto-registered
            (is (some (fn [t] (= :model/descriptor (:type/id t)))
                      (get-in resp [:body :types])))))
        (finally
          (xtnode/stop-node! node))))))
