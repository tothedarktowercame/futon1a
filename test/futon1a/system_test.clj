(ns futon1a.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.model.registry :as registry]
            [futon1a.scripts.seed-futon1-descriptors :as seed]
            [futon1a.system :as sys])
  (:import (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-system-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(defn- db-dir-uri-strings
  [config]
  {:tx-log (-> config :xtdb/tx-log :kv-store :db-dir str)
   :document-store (-> config :xtdb/document-store :kv-store :db-dir str)
   :index-store (-> config :xtdb/index-store :kv-store :db-dir str)})

(deftest start-registers-mission-id-gate
  (testing "boot registers the canonical :mission/doc id-contract — the L4 write-gate
            is active from start and survives a restart (E-futon1a-archivist durability)"
    (registry/clear-registry!)
    (is (nil? (registry/get-model :mission/doc)) "precondition: registry empty")
    (let [dir (temp-dir)
          s (sys/start! {:data-dir dir
                         :port 0
                         :allowed-penholders #{"tester"}})]
      (try
        (let [d (registry/get-model :mission/doc)]
          (is (some? d) "start! must register the :mission/doc contract so the gate is live from boot")
          (is (= seed/mission-doc-id-pattern (:id-pattern d)) "boot registers the verified id-pattern"))
        (finally
          ((:stop! s)))))))

(deftest start-requires-allowed-penholders-by-default
  (testing "boot fails loudly when no allowed penholders are configured"
    (let [dir (temp-dir)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"allowed-penholders required"
           (sys/start! {:data-dir dir
                        :port 0}))))))

(deftest start-hides-raw-handles-unless-explicit
  (testing "node/store are not exposed unless explicitly requested"
    (let [dir (temp-dir)
          s (sys/start! {:data-dir dir
                         :port 0
                         :allowed-penholders #{"tester"}})]
      (try
        (is (nil? (:node s)))
        (is (nil? (:store s)))
        (finally
          ((:stop! s))))))
  (testing "legacy internal handles can still be requested explicitly"
    (let [dir (temp-dir)
          s (sys/start! {:data-dir dir
                         :port 0
                         :allowed-penholders #{"tester"}
                         :expose-internals? true})]
      (try
        (is (some? (:node s)))
        (is (some? (:store s)))
        (finally
          ((:stop! s)))))))

(deftest start-validates-compat-penholder
  (testing "compat penholder must be part of allowed penholders"
    (let [dir (temp-dir)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"compat/penholder must be allowed"
           (sys/start! {:data-dir dir
                        :port 0
                        :allowed-penholders #{"api"}
                        :compat/penholder "joe"}))))))

(deftest xtdb-config-emits-canonical-windows-file-uris
  (doseq [{:keys [label data-dir expected]}
          [{:label "drive-letter path"
            :data-dir "C:\\futon1a\\data"
            :expected {:tx-log "file:/C:/futon1a/data/tx-log"
                       :document-store "file:/C:/futon1a/data/doc-store"
                       :index-store "file:/C:/futon1a/data/index-store"}}
           {:label "UNC path"
            :data-dir "\\\\server\\share\\futon1a"
            :expected {:tx-log "file:////server/share/futon1a/tx-log"
                       :document-store "file:////server/share/futon1a/doc-store"
                       :index-store "file:////server/share/futon1a/index-store"}}]]
    (testing label
      (is (= expected
             (db-dir-uri-strings (sys/xtdb-config {:data-dir data-dir})))))))
