;; textprobe-history-standalone.clj — M-text-sidecar P1 (history leg),
;; standalone variant for running against a PRIVATE COPY of the futon1a
;; store (e.g. on lucy) — no Drawbridge, no serving JVM, no quiet window.
;; The sibling textprobe-history-sample.clj is the live-JVM variant; this
;; one opens its own node, so it must NEVER be pointed at the data-dir of a
;; store another process has open (RocksDB will refuse the lock, but don't
;; try). See futon2/holes/M-text-sidecar.md.
;;
;; Usage (from the futon1a repo root):
;;   clojure -M scripts/textprobe-history-standalone.clj \
;;     <data-dir> <ids-file|FULL> <out-file>
;;
;;   data-dir — the store COPY (needs tx-log/ doc-store/ index-store/;
;;              if index-store was not copied, first boot rebuilds it from
;;              the golden stores — slow but fine on a private box)
;;   ids-file — history-sample-ids.edn from futon1b/textprobe_sample.clj,
;;              or the literal FULL to drain every source-bearing entity
;;              and every evidence doc (private-copy luxury: no sampling)
;;   out-file — EDN vector of {:id :band :n :versions [...]}; each version
;;              carries vt/tt and the text-bearing fields only
;;
;; First boot on a fresh copy replays/validates the tx-log — give it time
;; and watch stdout; a torn rsync of a live store shows up here as a
;; startup failure (fix = re-run rsync, which converges).

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[xtdb.api :as xtdb])

(defn- xtdb-config
  ;; Mirrors futon1a.system/xtdb-config (inlined so this script requires
  ;; nothing beyond xtdb.api — no jetty/ring load on a batch box).
  [data-dir]
  (let [kv (fn [sub] {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                 :db-dir (str (io/file data-dir sub))}})]
    {:xtdb/tx-log (kv "tx-log")
     :xtdb/document-store (kv "doc-store")
     :xtdb/index-store (kv "index-store")}))

(def text-paths
  ;; text-bearing field paths per the P1a census (graph + evidence)
  [[:entity/source]
   [:entity/name]
   [:entity/props :anchor/passage]
   [:entity/props :anchor/heading]
   [:evidence/body :text]])

(defn- extract [doc]
  (into {}
        (keep (fn [p]
                (let [v (get-in doc p)]
                  (when (string? v) [p v]))))
        text-paths))

(defn- full-work
  "Drain all source-bearing entity ids and all evidence ids via lazy open-q."
  [node]
  (let [db (xtdb/db node)
        drain (fn [q] (with-open [c (xtdb/open-q db q)]
                        (into [] (map first) (iterator-seq c))))]
    (concat
     (map #(vector :entity-full %) (drain '{:find [e] :where [[e :entity/source]]}))
     (map #(vector :evidence-full %) (drain '{:find [e] :where [[e :evidence/id]]})))))

(defn -main [data-dir ids-arg out-file]
  (println "[textprobe] opening node at" data-dir "…")
  (with-open [node (xtdb/start-node (xtdb-config data-dir))]
    (xtdb/sync node)
    (println "[textprobe] node synced.")
    (let [work (if (= ids-arg "FULL")
                 (full-work node)
                 (for [[band ids] (:bands (edn/read-string (slurp ids-arg)))
                       id ids]
                   [band id]))
          total (count work)]
      (println "[textprobe]" total "docs to walk.")
      (io/make-parents out-file)
      (with-open [w (io/writer out-file)]
        (.write w "[\n")
        (doseq [[i [band id]] (map-indexed vector work)]
          (let [hist (xtdb/entity-history (xtdb/db node) id :asc {:with-docs? true})
                versions (mapv (fn [h]
                                 {:vt (:xtdb.api/valid-time h)
                                  :tt (:xtdb.api/tx-time h)
                                  :deleted? (nil? (:xtdb.api/doc h))
                                  :fields (extract (:xtdb.api/doc h))})
                               hist)]
            (.write w (pr-str {:id id :band band :n (count versions) :versions versions}))
            (.write w "\n"))
          (when (zero? (mod (inc i) 5000))
            (println "[textprobe]" (inc i) "/" total)))
        (.write w "]\n"))
      (println "[textprobe] done:" total "docs →" out-file))))

(apply -main *command-line-args*)
