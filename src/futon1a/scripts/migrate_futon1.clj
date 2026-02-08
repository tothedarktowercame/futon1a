(ns futon1a.scripts.migrate-futon1
  "Phase II storage migration: futon1 XTDB -> futon1a XTDB.

   Goal: copy the *current world state* (latest docs) from a futon1 XTDB store
   into a fresh futon1a XTDB store, and verify equivalence via a stable checksum.

   This is a logical export/import (query docs + submit puts), not a directory
   copy, because futon1 commonly uses LMDB while futon1a uses RocksDB."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [futon1a.system :as system]
            [xtdb.api :as xtdb])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(defn- usage []
  (str "Usage: clojure -M:migrate -m futon1a.scripts.migrate-futon1 \\\n"
       "  --src-xtdb-dir DIR --src-backend lmdb|rocksdb \\\n"
       "  --dest-data-dir DIR [--batch N] [--out PATH]\n\n"
       "Notes:\n"
       "- --src-xtdb-dir should contain tx-log/, doc-store/, index-store/.\n"
       "- --dest-data-dir is futon1a's base dir (it will create tx-log/, doc-store/, index-store/).\n"
       "- Output is EDN with checksums and counts.\n"))

(defn- parse-long* [s]
  (when (and (string? s) (re-matches #"-?\\d+" s))
    (Long/parseLong s)))

(defn- parse-args [args]
  (loop [args args opts {:batch 200}]
    (if-not (seq args)
      opts
      (case (first args)
        "--help" (do
                   (println (usage))
                   (System/exit 0))
        "--src-xtdb-dir" (recur (nnext args) (assoc opts :src-xtdb-dir (second args)))
        "--src-backend" (recur (nnext args) (assoc opts :src-backend (keyword (second args))))
        "--dest-data-dir" (recur (nnext args) (assoc opts :dest-data-dir (second args)))
        "--batch" (recur (nnext args) (assoc opts :batch (or (some-> (second args) parse-long*) 200)))
        "--out" (recur (nnext args) (assoc opts :out (second args)))
        (throw (ex-info (str "Unknown arg: " (first args)) {:args args}))))))

(defn- sha256 []
  (MessageDigest/getInstance "SHA-256"))

(defn- hex
  [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (.append sb (format "%02x" (bit-and (aget bs i) 0xff))))
    (str sb)))

(declare stable-str)

(def ^:private canonical-key-cmpr
  (fn [a b]
    (compare (stable-str a) (stable-str b))))

(defn- canonicalize
  "Canonicalize a value into a form with deterministic `pr-str`.

   Why: XTDB can return maps/sets whose iteration order differs across
   backends (LMDB vs RocksDB) even when logical contents are equal.
   We normalize maps/sets recursively and also coerce non-EDN JVM objects
   into deterministic EDN so checksums are stable across runs/backends."
  [v]
  (cond
    (map? v)
    (into (sorted-map-by canonical-key-cmpr)
          (map (fn [[k vv]] [k (canonicalize vv)]))
          v)

    (set? v)
    (vec (sort-by stable-str (map canonicalize v)))

    (sequential? v)
    (mapv canonicalize v)

    ;; Normalize common JVM value types that compare by value but print with
    ;; identity hashes (e.g. `#object[...]`), which would break checksums.
    (instance? java.util.Date v)
    {:__type :inst
     :millis (.getTime ^java.util.Date v)}

    (instance? java.time.Instant v)
    {:__type :instant
     :iso (str v)}

    (instance? java.time.temporal.TemporalAccessor v)
    {:__type :temporal
     :class (.getName (class v))
     :iso (str v)}

    (instance? java.util.UUID v)
    {:__type :uuid
     :value (str v)}

    ;; Leave basic EDN scalars alone.
    (or (nil? v) (string? v) (keyword? v) (symbol? v)
        (number? v) (boolean? v) (char? v))
    v

    ;; Fallback: deterministic object representation.
    :else
    {:__type :object
     :class (.getName (class v))
     :str (str v)}))

(defn stable-str
  "Deterministic `pr-str` for any EDN-ish value (including map/set ids)."
  [v]
  (pr-str (canonicalize v)))

(defn list-entity-ids
  "Best-effort enumeration of all documents in XTDB.

   XTDB stores docs with an :xt/id field; this query should return every entity id.
   Returns a seq of entity ids."
  [db]
  (->> (xtdb/q db '{:find [e]
                    :where [[e :xt/id _]]})
       (map first)))

(defn checksum-world*
  "Compute a stable checksum over the world state in XTDB for a specific id list.

   The checksum is the SHA-256 over `pr-str` of each doc (canonicalized) in id order,
   separated by newlines.

   Returns {:ids <n> :docs <n> :missing <n> :sha256 <hex>}."
  [node ids]
  (let [db (xtdb/db node)
        ids (vec ids)
        md (sha256)]
    (loop [xs ids docs 0 missing 0]
      (if-not (seq xs)
        {:ids (count ids)
         :docs docs
         :missing missing
         :sha256 (hex (.digest md))}
        (let [eid (first xs)]
          (if-let [doc (xtdb/entity db eid)]
            (do
              (.update md (.getBytes (pr-str (canonicalize doc)) StandardCharsets/UTF_8))
              (.update md (byte-array [(byte 10)]))
              (recur (rest xs) (inc docs) missing))
            (recur (rest xs) docs (inc missing))))))))

(defn- truncate-str
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "..."))))

(defn- doc-summary
  "Return a small, EDN-friendly summary of how two docs differ."
  [src-doc dest-doc]
  (let [sk (set (keys src-doc))
        dk (set (keys dest-doc))
        src-only (sort-by stable-str (seq (set/difference sk dk)))
        dest-only (sort-by stable-str (seq (set/difference dk sk)))
        common (set/intersection sk dk)
        different (->> common
                       (filter (fn [k] (not= (get src-doc k) (get dest-doc k))))
                       (sort-by stable-str)
                       (take 25)
                       (vec))
        examples (->> different
                      (take 5)
                      (mapv (fn [k]
                              {:k k
                               :src (truncate-str (pr-str (get src-doc k)) 240)
                               :dest (truncate-str (pr-str (get dest-doc k)) 240)})))]
    {:src-only-keys (vec src-only)
     :dest-only-keys (vec dest-only)
     :different-keys different
     :examples examples}))

(defn first-mismatch
  "Find the first entity id (in the provided id list) whose canonicalized doc differs.
   Returns {:id ... :summary ...} or nil."
  [src-node dest-node ids]
  (let [src-db (xtdb/db src-node)
        dest-db (xtdb/db dest-node)]
    (loop [ids ids]
      (when-let [eid (first ids)]
        (let [sd (some-> (xtdb/entity src-db eid) stable-str)
              dd (some-> (xtdb/entity dest-db eid) stable-str)]
          (if (= sd dd)
            (recur (rest ids))
            {:id eid
             :summary {:src (truncate-str sd 600)
                       :dest (truncate-str dd 600)}})))))) 

(defn start-src-node!
  "Start a source XTDB node for futon1 data.

   backend:
   - :lmdb (common in futon1)
   - :rocksdb"
  [{:keys [xtdb-dir backend]}]
  (when-not (and (string? xtdb-dir) (seq xtdb-dir))
    (throw (ex-info "src xtdb-dir required" {:xtdb-dir xtdb-dir})))
  (let [dir (-> xtdb-dir io/file .getAbsolutePath)
        module (case backend
                 :lmdb 'xtdb.lmdb/->kv-store
                 :rocksdb 'xtdb.rocksdb/->kv-store
                 (throw (ex-info "unsupported backend" {:backend backend})))
        kv (fn [subdir]
             {:kv-store {:xtdb/module module
                         :db-dir (str dir "/" subdir)}})]
    (xtdb/start-node {:xtdb/tx-log (kv "tx-log")
                      :xtdb/document-store (kv "doc-store")
                      :xtdb/index-store (kv "index-store")})))

(defn start-dest-node!
  "Start a destination XTDB node using futon1a's persistent config."
  [{:keys [data-dir]}]
  (xtdb/start-node (system/xtdb-config {:data-dir data-dir})))

(defn import-world!
  "Import the world state from src-node into dest-node.

   Returns {:imported <n> :tx-ids [...]}"
  [{:keys [src-node dest-node batch]}]
  (let [src-db (xtdb/db src-node)
        ids (->> (list-entity-ids src-db)
                 (sort-by stable-str))
        docs (keep (fn [eid] (xtdb/entity src-db eid)) ids)
        batches (partition-all (long (max 1 batch)) docs)]
    (loop [batches batches imported 0 tx-ids []]
      (if-not (seq batches)
        {:imported imported :tx-ids tx-ids}
        (let [docs (first batches)
              ops (mapv (fn [doc] [:xtdb.api/put doc]) docs)
              tx (xtdb/submit-tx dest-node ops)
              tx-id (or (:xtdb.api/tx-id tx) (:tx-id tx) tx)]
          (xtdb/await-tx dest-node tx)
          (recur (rest batches)
                 (+ imported (count docs))
                 (conj tx-ids (str tx-id))))))))

(defn migrate!
  "Run a full migration and verification.

   Returns EDN-friendly summary."
  [{:keys [src-xtdb-dir src-backend dest-data-dir batch]}]
  (when-not src-xtdb-dir
    (throw (ex-info "--src-xtdb-dir required" {})))
  (when-not dest-data-dir
    (throw (ex-info "--dest-data-dir required" {})))
  (let [backend (or src-backend :lmdb)
        src-node (start-src-node! {:xtdb-dir src-xtdb-dir :backend backend})
        dest-node (start-dest-node! {:data-dir dest-data-dir})]
    (try
      (let [src-db (xtdb/db src-node)
            dest-db (xtdb/db dest-node)
            src-ids (->> (list-entity-ids src-db) (sort-by stable-str) vec)
            _ (when (zero? (count src-ids))
                (throw (ex-info "source appears empty (0 docs found); check --src-xtdb-dir"
                                {:src-xtdb-dir src-xtdb-dir
                                 :backend backend})))
            import (import-world! {:src-node src-node :dest-node dest-node :batch batch})
            src-sum (checksum-world* src-node src-ids)
            dest-sum (checksum-world* dest-node src-ids)
            ok? (and (= (:sha256 src-sum) (:sha256 dest-sum))
                     (zero? (:missing dest-sum)))
            mismatch (when-not ok?
                       (first-mismatch src-node dest-node src-ids))]
        {:ok? ok?
         :src {:xtdb-dir src-xtdb-dir
               :backend backend
               :ids (:ids src-sum)
               :docs (:docs src-sum)
               :missing (:missing src-sum)
               :sha256 (:sha256 src-sum)}
         :dest {:data-dir dest-data-dir
                :ids (:ids dest-sum)
                :docs (:docs dest-sum)
                :missing (:missing dest-sum)
                :sha256 (:sha256 dest-sum)}
         :import import
         :mismatch mismatch})
      (finally
        (.close dest-node)
        (.close src-node)))))

(defn -main [& args]
  (let [{:keys [out] :as opts} (parse-args args)
        result (migrate! opts)
        payload (assoc result :generated-at (System/currentTimeMillis))]
    (prn payload)
    (when out
      (spit out (pr-str payload))
      (println "wrote" out))
    (when-not (:ok? payload)
      (System/exit 2))))
