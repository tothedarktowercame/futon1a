(ns futon1a.scripts.ingest-patterns-index-sigils
  "Ingest Futon3's patterns-index.tsv into Futon1a as :pattern/has-sigil links.

   Futon3's XTDB parity tests assume:
   - every standard-library pattern has at least one outgoing :pattern/has-sigil
     relation, and
   - GET /api/alpha/entities/latest?type=pattern/library enumerates only those
     sigiled patterns.

   This script is an explicit migration step: it reads a TSV index and writes
   deterministic sigil entities plus :pattern/has-sigil relations."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.core.xtdb-node :as xtnode]
            [futon1a.system :as system]
            [xtdb.api :as xtdb]))

(defn- stable-sigil-id [ok truth]
  (str "sigil|" ok "|" truth))

(defn- stable-sigil-name [ok truth]
  (str ok "/" truth))

(defn- stable-sigil-rel-id [src-id dst-id]
  (str "rel|" src-id "|pattern/has-sigil|" dst-id))

(defn- parse-index
  "Return seq of {:pattern-id :okipona :truth :rationale :hotwords} from TSV."
  [path]
  (with-open [r (io/reader (io/file path))]
    (->> (line-seq r)
         (remove #(str/starts-with? % "#"))
         (map str/trim)
         (remove str/blank?)
         (map (fn [line]
                (let [[pattern-id ok truth rationale hotwords] (str/split line #"\t" 5)]
                  {:pattern-id (some-> pattern-id str/trim)
                   :okipona (some-> ok str/trim)
                   :truth (some-> truth str/trim)
                   :rationale (some-> rationale str/trim)
                   :hotwords (some-> hotwords str/trim)})))
         (remove (fn [row] (str/blank? (:pattern-id row))))
         (vec))))

(defn- canonical-pattern-entity-id
  "Return canonical :entity/id for a pattern/library entity with this :entity/name.

   If duplicates exist, choose the lexicographically-smallest entity id."
  [node pattern-name]
  (let [db (xtdb/db node)]
    (->> (xtdb/q db '{:find [eid]
                      :in [name]
                      :where [[e :entity/name name]
                              [e :entity/type :pattern/library]
                              [e :entity/id eid]]}
                 pattern-name)
         (map first)
         (map str)
         (sort)
         (first))))

(defn ingest!
  "Write sigil entities and :pattern/has-sigil relations for patterns-index.tsv.

   Inputs:
   - node, store
   - penholder, allowed-penholders
   - index-path (string)

   Returns {:ok? true :rows N :tx-ops M} or throws when required patterns missing."
  [{:keys [node store penholder allowed-penholders index-path] :as ctx}]
  (when-not (and node store)
    (throw (ex-info "node and store required" {:ctx (keys ctx)})))
  (when-not (and (string? index-path) (seq (str/trim index-path)))
    (throw (ex-info "index-path required" {:index-path index-path})))
  (let [rows (parse-index index-path)
        missing (->> rows
                     (keep (fn [{:keys [pattern-id]}]
                             (when-not (canonical-pattern-entity-id node pattern-id)
                               pattern-id)))
                     (vec))]
    (when (seq missing)
      (throw (ex-info "Some patterns-index rows do not exist as :pattern/library entities; sync patterns first."
                      {:missing-count (count missing)
                       :missing (take 25 missing)})))
    (let [tx-ops
          (->> rows
               (mapcat
                (fn [{:keys [pattern-id okipona truth rationale hotwords]}]
                  (let [src-id (canonical-pattern-entity-id node pattern-id)
                        ok (or (some-> okipona str/trim not-empty) "unknown")
                        tr (or (some-> truth str/trim not-empty) "?")
                        sigil-id (stable-sigil-id ok tr)
                        sigil-name (stable-sigil-name ok tr)
                        sigil-doc (cond-> {:xt/id sigil-id
                                           :entity/id sigil-id
                                           :entity/name sigil-name
                                           :entity/type :pattern/sigil
                                           :entity/external-id sigil-name
                                           :entity/source (or rationale "patterns-index")}
                                    (seq hotwords) (assoc :sigil/hotwords hotwords)
                                    (seq okipona) (assoc :sigil/okipona okipona)
                                    (seq truth) (assoc :sigil/truth truth))
                        rid (stable-sigil-rel-id src-id sigil-id)
                        rel-doc {:xt/id rid
                                 :relation/id rid
                                 :relation/type :pattern/has-sigil
                                 :relation/src src-id
                                 :relation/dst sigil-id
                                 :relation/from src-id
                                 :relation/to sigil-id
                                 :relation/provenance {:note ":pattern/has-sigil"
                                                       :source "futon1a patterns-index"}}]
                    [[:xtdb.api/put sigil-doc]
                     [:xtdb.api/put rel-doc]])))
               (vec))]
      (when (seq tx-ops)
        (pipeline/run-write! {:store store
                              :penholder penholder
                              :allowed-penholders (or allowed-penholders #{})
                              :model {:id 1}
                              :required-keys #{:id}
                              :identity nil
                              :tx-ops tx-ops
                              :claim {:op :ingest/patterns-index-sigils
                                      :index-path index-path}
                              :detail {:rows (count rows)}}))
      {:ok? true
       :rows (count rows)
       :tx-ops (count tx-ops)})))

(defn -main
  [& args]
  (let [data-dir (System/getenv "FUTON1A_DATA_DIR")
        index-path (or (first args)
                       (System/getenv "FUTON1A_PATTERNS_INDEX_TSV"))]
    (when-not (and (string? data-dir) (seq (str/trim data-dir)))
      (throw (ex-info "FUTON1A_DATA_DIR required" {})))
    (when-not (and (string? index-path) (seq (str/trim index-path)))
      (throw (ex-info "index path required (argv[0] or FUTON1A_PATTERNS_INDEX_TSV)" {})))
    (let [node (xtnode/start-node! (system/xtdb-config {:data-dir data-dir}))
          store (xtnode/xtdb-store node)]
      (try
        (let [ph (or (some-> (System/getenv "FUTON1A_COMPAT_PENHOLDER") str/trim not-empty)
                     "cli")]
          (println (pr-str (ingest! {:node node
                                     :store store
                                     :penholder ph
                                     :allowed-penholders #{ph}
                                     :index-path index-path}))))
        (finally
          (xtnode/stop-node! node))))))

