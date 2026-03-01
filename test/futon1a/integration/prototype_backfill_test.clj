(ns futon1a.integration.prototype-backfill-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.scripts.backfill-prototypes-as-patterns :as backfill]
            [futon1a.system :as sys]
            [xtdb.api :as xtdb])
  (:import (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-xtdb-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(deftest backfill-promotes-prototypes
  (testing "devmap/prototype -> pattern/library + includes + sigil link"
    (let [dir (temp-dir)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}
                            :expose-internals? true})
          node (:node sys1)
          store (:store sys1)]
      (try
        (xtdb/submit-tx node [[::xtdb/put {:xt/id "p0"
                                          :entity/id "p0"
                                          :entity/name "f0/p0"
                                          :entity/type :devmap/prototype
                                          :entity/source "futon3/devmap"}]
                              [::xtdb/put {:xt/id "sig"
                                          :entity/id "sig"
                                          :entity/name "sig"
                                          :entity/type :sigil
                                          :entity/source "futon3/sigil"}]
                              [::xtdb/put {:xt/id "r"
                                          :relation/id "r"
                                          :relation/type :prototype/has-sigil
                                          :relation/src "p0"
                                          :relation/dst "sig"}]])
        (xtdb/sync node)
        (let [res (backfill/backfill! {:node node
                                       :store store
                                       :penholder "tester"
                                       :allowed-penholders #{"tester"}})]
          (is (:ok? res))
          (let [db (xtdb/db node)
                promoted (xtdb/entity db "p0")]
            (is (= :pattern/library (:entity/type promoted))))
          (let [db (xtdb/db node)
                rels (xtdb/q db '{:find [r]
                                 :where [[r :relation/type :pattern/has-sigil]]})]
            (is (= 1 (count rels)))))
        (finally
          ((:stop! sys1)))))))
