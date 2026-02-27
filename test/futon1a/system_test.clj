(ns futon1a.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
  (:import (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-system-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

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
