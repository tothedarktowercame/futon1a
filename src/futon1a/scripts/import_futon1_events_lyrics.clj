(ns futon1a.scripts.import-futon1-events-lyrics
  "Import Arxana media lyrics from Futon1's event log (events.ndjson) into futon1a.

  Why this exists:
  - Futon1 sometimes persisted lyrics primarily in the durable event log, not in
    the Futon1 XTDB store that earlier migrations focused on.
  - Arxana reads lyrics entities by external-id-like string ids (e.g.
    \"arxana/media-lyrics/misc/<sha>\"); futon1a must contain corresponding XTDB
    docs keyed by that id."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [xtdb.api :as xtdb]))

(defn- safe-read-edn
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (binding [*read-eval* false]
      (edn/read-string s))))

(defn- lyrics-canonical-id
  "Derive the canonical lyrics id string from a Futon1 entity map.

  Futon1 had multiple representations:
  - Newer: :id is already a string external id like arxana/media-lyrics/...
  - Older: :id is a UUID but :name encodes the external id-ish string.
  - Sometimes :external-id is present and is the desired id."
  [entity]
  (let [id (:id entity)
        ext (:external-id entity)
        name (:name entity)]
    (cond
      (and (string? id) (seq (str/trim id))) (str/trim id)
      (and (string? ext) (seq (str/trim ext))) (str/trim ext)
      (and (string? name)
           (seq (str/trim name))
           (str/starts-with? (str/trim name) "arxana/media-lyrics/")) (str/trim name)
      :else nil)))

(defn- entity->lyrics-doc
  "Convert a Futon1 lyrics entity map to a futon1a XTDB doc."
  [entity]
  (let [id (lyrics-canonical-id entity)
        name (some-> (:name entity) str)
        typ (:type entity)
        src (:source entity)
        sha (or (:media/sha256 entity)
                ;; If missing, attempt to take the last segment as sha, but only
                ;; for canonical ids we understand.
                (when (and (string? id) (str/includes? id "/"))
                  (last (str/split id #"/"))))]
    (when (and id (= :arxana/media-lyrics typ))
      {:xt/id id
       :entity/id id
       :entity/type :arxana/media-lyrics
       :entity/name name
       :entity/external-id id
       :entity/source src
       :media/sha256 sha
       ;; Preserve optional telemetry if present.
       :entity/last-seen (:last-seen entity)
       :entity/seen-count (:seen-count entity)
       :entity/pinned? (:pinned? entity)})))

(defn parse-events
  "Parse Futon1 events.ndjson and return the final world-state lyric docs.

  Returns {:docs [...] :stats {...}}."
  [{:keys [events-path]}]
  (when-not (and (string? events-path) (seq (str/trim events-path)))
    (throw (ex-info "events-path required" {:events-path events-path})))
  (with-open [r (io/reader events-path)]
    (loop [line-no 0
           active {}
           stats {:lines 0
                  :json/invalid 0
                  :edn/invalid 0
                  :events/ignored 0
                  :lyrics/upserts 0
                  :lyrics/retracts 0
                  :lyrics/skipped 0}]
      (if-let [line (.readLine r)]
        (let [line-no (inc line-no)
              stats0 (update stats :lines inc)
              [next-active next-stats]
              (try
                (let [j (json/decode line true)
                      ev (safe-read-edn (or (get j "edn") (:edn j)))
                      t (:type ev)]
                  (cond
                    (and (= t :entity/upsert)
                         (= :arxana/media-lyrics (get-in ev [:entity :type])))
                    (let [ent (:entity ev)
                          id (lyrics-canonical-id ent)
                          doc (entity->lyrics-doc ent)]
                      (if (and id doc)
                        [(assoc active id doc) (update stats0 :lyrics/upserts inc)]
                        [active (update stats0 :lyrics/skipped inc)]))

                    (and (= t :entity/retract)
                         (= :arxana/media-lyrics (get-in ev [:entity :type])))
                    (let [ent (:entity ev)
                          id (lyrics-canonical-id ent)]
                      (if id
                        [(dissoc active id) (update stats0 :lyrics/retracts inc)]
                        [active (update stats0 :lyrics/skipped inc)]))

                    :else
                    [active (update stats0 :events/ignored inc)]))
                (catch com.fasterxml.jackson.core.JsonParseException _
                  [active (update stats0 :json/invalid inc)])
                (catch Exception _
                  ;; If EDN parse fails or shape is unexpected, count and continue.
                  [active (update stats0 :edn/invalid inc)]))]
          (recur line-no next-active next-stats))
        (let [docs (->> (vals active)
                        ;; Deterministic order for repeatability.
                        (sort-by :xt/id)
                        (vec))]
          {:docs docs
           :stats (assoc stats
                         :lyrics/final (count docs))})))))

(defn import!
  "Import lyrics docs into the given store via open-world pipeline.

  Inputs:
  - store, penholder, allowed-penholders
  - events-path
  - dry-run? (boolean, optional)

  Returns {:ok? true ...} (and tx metadata when not dry-run)."
  [{:keys [store penholder allowed-penholders events-path dry-run? tooling allowed-tooling]}]
  (when-not store
    (throw (ex-info "store required" {:store store})))
  (when-not (and (string? penholder) (seq (str/trim penholder)))
    (throw (ex-info "penholder required" {:penholder penholder})))
  (let [{:keys [docs stats]} (parse-events {:events-path events-path})
        entities (mapv (fn [doc] {:entity/id (:entity/id doc) :entity/type (:entity/type doc)}) docs)
        tx-ops (mapv (fn [doc] [::xtdb/put doc]) docs)
        base {:ok? true
              :stats stats
              :counts {:entities (count entities)
                       :tx-ops (count tx-ops)}}]
    (if dry-run?
      (assoc base :dry-run? true)
      (merge base
             (pipeline/run-open-world!
              {:store store
               :penholder penholder
               :allowed-penholders (or allowed-penholders #{})
               :tooling tooling
               :allowed-tooling (or allowed-tooling #{})
               :entities entities
               :relations []
               :require-model? false
               :tx-ops tx-ops
               :claim {:op :migrate/futon1-events-lyrics}
               :detail {:events-path events-path
                        :docs (count docs)}})))))
