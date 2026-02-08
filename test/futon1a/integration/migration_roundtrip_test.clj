(ns futon1a.integration.migration-roundtrip-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.scripts.migrate-futon1 :as mig]
            [xtdb.api :as xtdb]))

(def mem-config
  {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}})

(deftest migration-copies-world-state-and-checksum-matches
  (testing "copy world state from src -> dest yields identical checksums"
    (let [src (xtdb/start-node mem-config)
          dest (xtdb/start-node mem-config)]
      (try
        (xtdb/await-tx src (xtdb/submit-tx src [[:xtdb.api/put {:xt/id "e1" :v 1}]
                                               [:xtdb.api/put {:xt/id "e2" :v 2}]
                                               [:xtdb.api/put {:xt/id :kw/id :v 3}]]))
        (let [ids (->> (mig/list-entity-ids (xtdb/db src))
                       (sort-by pr-str)
                       (vec))
              src-sum (mig/checksum-world* src ids)]
          (is (= 3 (:ids src-sum)))
          (is (= 3 (:docs src-sum)))
          (mig/import-world! {:src-node src :dest-node dest :batch 2})
          (let [dest-sum (mig/checksum-world* dest ids)]
            (is (= (:sha256 src-sum) (:sha256 dest-sum)))))
        (finally
          (.close dest)
          (.close src))))))
