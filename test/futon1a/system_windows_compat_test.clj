(ns futon1a.system-windows-compat-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys]))

(defn- db-dir
  [cfg k]
  (get-in cfg [k :kv-store :db-dir]))

(deftest ^:skeleton xtdb-config-emits-file-uri-values
  (testing "TODO(follow-up): assert each XTDB db-dir is a file URI"
    (let [cfg (sys/xtdb-config {:data-dir "tmp/futon1a-windows-compat-skeleton"})]
      (is (map? cfg))
      (is (contains? cfg :xtdb/tx-log))
      (is (contains? cfg :xtdb/document-store))
      (is (contains? cfg :xtdb/index-store))
      (is true "TODO: assert URI type + file scheme for all :db-dir values"))))

(deftest ^:skeleton xtdb-config-windows-drive-letter-path
  (testing "TODO(follow-up): verify C:\\ style path normalization"
    (let [cfg (sys/xtdb-config {:data-dir "C:\\futon1a\\data"})
          tx-uri (str (db-dir cfg :xtdb/tx-log))]
      (is (string? tx-uri))
      (is true "TODO: assert canonical URI form for drive-letter paths"))))

(deftest ^:skeleton xtdb-config-windows-unc-path
  (testing "TODO(follow-up): verify UNC path normalization"
    (let [cfg (sys/xtdb-config {:data-dir "\\\\server\\share\\futon1a"})
          index-uri (str (db-dir cfg :xtdb/index-store))]
      (is (string? index-uri))
      (is true "TODO: assert canonical URI form for UNC paths"))))
