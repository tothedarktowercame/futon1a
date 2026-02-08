(ns futon1a.stress.durability-stress-test
  "Stress tests for concurrent durable-write! and EDN append."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [futon1a.core.xtdb :as xt]
            [futon1a.diag.proof-path :as proof])
  (:import (java.io File)
           (java.util.concurrent CountDownLatch)))

(def ^:private thread-count 50)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- run-concurrent
  "Run `f` on `n` threads, all starting simultaneously via a CountDownLatch.
   Returns a vector of results (or ExceptionInfo wrappers)."
  [n f]
  (let [latch   (CountDownLatch. 1)
        results (atom [])
        threads (mapv (fn [i]
                        (Thread.
                         (fn []
                           (.await latch)
                           (let [r (try (f i) (catch Exception e e))]
                             (swap! results conj r)))))
                      (range n))]
    (doseq [^Thread t threads] (.start t))
    (.countDown latch)
    (doseq [^Thread t threads] (.join t))
    @results))

(defn- temp-file ^File [prefix]
  (let [f (File/createTempFile prefix ".edn")]
    (.deleteOnExit f)
    f))

;; ---------------------------------------------------------------------------
;; (a) Concurrent durable-write! — no file append
;; ---------------------------------------------------------------------------

(deftest concurrent-durable-write-no-append
  (testing "N concurrent durable-write! calls each produce a valid proof-path"
    (let [store   (xt/->StubStore)
          results (run-concurrent
                   thread-count
                   (fn [i]
                     (xt/durable-write!
                      {:store    store
                       :actor    (str "actor-" i)
                       :claim    {:op :stress-test}
                       :detail   {:thread i}
                       :write-fn (fn [] [{:xt/id (str "doc-" i)}])})))]

      (testing "no exceptions thrown"
        (let [errors (filter #(instance? Exception %) results)]
          (is (empty? errors)
              (str "Exceptions: " (mapv #(.getMessage ^Exception %) errors)))))

      (testing "every result has a valid complete proof-path"
        (doseq [r results]
          (when-not (instance? Exception r)
            (is (:ok? (proof/validate-complete-path (:path r)))))))

      (testing "every result has a tx-id"
        (doseq [r results]
          (when-not (instance? Exception r)
            (is (string? (:tx-id r)))))))))

;; ---------------------------------------------------------------------------
;; (b) Concurrent EDN file append
;; ---------------------------------------------------------------------------

(deftest concurrent-durable-write-edn-append
  (testing "N concurrent writes to the same proof-log file"
    (let [store   (xt/->StubStore)
          logfile (temp-file "futon1a-stress-append")
          logpath (.getPath logfile)
          results (run-concurrent
                   thread-count
                   (fn [i]
                     (xt/durable-write!
                      {:store          store
                       :actor          (str "actor-" i)
                       :claim          {:op :stress-append}
                       :detail         {:thread i}
                       :write-fn       (fn [] [{:xt/id (str "doc-" i)}])
                       :proof-log-path logpath})))]

      (testing "no exceptions thrown"
        (let [errors (filter #(instance? Exception %) results)]
          (is (empty? errors)
              (str "Exceptions: " (mapv #(.getMessage ^Exception %) errors)))))

      (let [raw-lines (str/split-lines (slurp logfile))
            lines     (remove str/blank? raw-lines)]

        (testing "line count equals thread count (no lost writes)"
          (is (= thread-count (count lines))))

        (testing "every line parses as valid EDN"
          (doseq [line lines]
            (is (map? (edn/read-string line))
                (str "Failed to parse: " (subs line 0 (min 80 (count line)))))))

        (testing "every line contains a :path/id"
          (doseq [line lines]
            (let [m (edn/read-string line)]
              (is (string? (:path/id m))))))

        (testing "no duplicate :path/id values"
          (let [ids (map #(:path/id (edn/read-string %)) lines)]
            (is (= (count ids) (count (set ids))))))))))

;; ---------------------------------------------------------------------------
;; (c) Concurrent durable-write! with contention (latency in tx-sync!)
;; ---------------------------------------------------------------------------

(defrecord SlowStore [latency-ms counter]
  xt/DurableStore
  (submit-tx! [_ _]
    (str "tx-" (swap! counter inc)))
  (tx-sync! [_ _]
    (Thread/sleep latency-ms)
    true)
  (entity [_ _] nil))

(deftest concurrent-durable-write-with-contention
  (testing "N concurrent writes with simulated tx-sync! latency"
    (let [store   (->SlowStore 5 (atom 0))
          logfile (temp-file "futon1a-stress-contention")
          logpath (.getPath logfile)
          results (run-concurrent
                   thread-count
                   (fn [i]
                     (xt/durable-write!
                      {:store          store
                       :actor          (str "actor-" i)
                       :claim          {:op :stress-contention}
                       :detail         {:thread i}
                       :write-fn       (fn [] [{:xt/id (str "doc-" i)}])
                       :proof-log-path logpath})))]

      (testing "no exceptions thrown"
        (let [errors (filter #(instance? Exception %) results)]
          (is (empty? errors)
              (str "Exceptions: " (mapv #(.getMessage ^Exception %) errors)))))

      (let [raw-lines (str/split-lines (slurp logfile))
            lines     (remove str/blank? raw-lines)]

        (testing "line count equals thread count (no lost writes)"
          (is (= thread-count (count lines))))

        (testing "every line parses as valid EDN"
          (doseq [line lines]
            (is (map? (edn/read-string line))
                (str "Failed to parse: " (subs line 0 (min 80 (count line)))))))

        (testing "every line contains a :path/id"
          (doseq [line lines]
            (let [m (edn/read-string line)]
              (is (string? (:path/id m))))))

        (testing "no duplicate :path/id values"
          (let [ids (map #(:path/id (edn/read-string %)) lines)]
            (is (= (count ids) (count (set ids))))))))))
