(ns futon1a.compat.futon1-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.compat.futon1-graph :as f1g]
            [futon1a.core.xtdb :as layer0]
            [futon1a.core.xtdb-node :as xtnode]
            [xtdb.api :as xtdb]))

(def xtdb-config
  {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}})

(deftest fetch-entity-falls-back-to-external-id
  (testing "fetch-entity can resolve a doc by :entity/external-id when :xt/id differs"
    (let [node (xtnode/start-node! xtdb-config)]
      (try
        (let [store (xtnode/xtdb-store node)
              doc {:xt/id "eid-1"
                   :entity/id "eid-1"
                   :entity/type :arxana/media-lyrics
                   :entity/name "salt (lyrics)"
                   :entity/external-id "arxana/media-lyrics/misc/abc"
                   :entity/source "lyrics..."
                   :media/sha256 "abc"}
              _ (layer0/durable-write-tx!
                 {:store store
                  :actor "tester"
                  :claim {:op :put}
                  :detail {:layer :test}
                  :tx-ops [[::xtdb/put doc]]})
              fetched (f1g/fetch-entity node "arxana/media-lyrics/misc/abc")]
          (is fetched)
          (is (= "eid-1" (str (or (:xt/id fetched) (:entity/id fetched))))))
        (finally
          (xtnode/stop-node! node))))))

