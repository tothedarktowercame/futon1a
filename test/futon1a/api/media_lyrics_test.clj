(ns futon1a.api.media-lyrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.api.routes :as routes]
            [futon1a.core.xtdb-node :as xtnode]
            [xtdb.api :as xtdb]))

(def xtdb-config
  {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}})

(deftest upsert-media-lyrics-writes-lyrics-entity
  (testing "upsert-media-lyrics writes an :arxana/media-lyrics entity keyed by string id"
    (let [node (xtnode/start-node! xtdb-config)]
      (try
        (let [store (xtnode/xtdb-store node)
              lyrics-id "arxana/media-lyrics/misc/abc"
              resp (routes/upsert-media-lyrics
                    {:store store
                     :penholder "tester"
                     :allowed-penholders #{"tester"}
                     :profile "default"
                     :payload {:lyrics {:id lyrics-id
                                        :name "salt (lyrics)"
                                        :type "arxana/media-lyrics"
                                        :source "lyrics..."
                                        :media/sha256 "abc"}}})
              body (:body resp)
              db (xtdb/db node)
              doc (xtdb/entity db lyrics-id)]
          (is (= 200 (:status resp)))
          (is (= lyrics-id (get-in body [:lyrics :id])))
          (is (= :arxana/media-lyrics (:entity/type doc)))
          (is (= "lyrics..." (:entity/source doc))))
        (finally
          (xtnode/stop-node! node))))))

