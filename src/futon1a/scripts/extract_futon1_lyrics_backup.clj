(ns futon1a.scripts.extract-futon1-lyrics-backup
  "Extract Arxana media-lyrics payloads from Futon1's durable logs into the
  JSON 'lyrics data recovery' format used by futon1a's importer.

  Primary use: recover lyrics that were successfully written (or at least
  durably logged) even when Futon1's query layer did not reflect them.

  This is an explicit operational tool (T7)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon1a.scripts.import-lyrics-recovery :as lyrics-recovery])
  (:import (java.io File)))

(defn- usage []
  (str/join
   "\n"
   ["Usage:"
    "  clojure -M -m futon1a.scripts.extract-futon1-lyrics-backup \\"
    "    --audio-dir <dir> --out <json> [--in <ednlog>] [--ep <name>]"
    ""
    "Defaults:"
    "  --in  /home/joe/code/futon1/data/default/logs/write-commits.ednlog"
    "  --ep  <basename of audio-dir>"
    ""
    "Notes:"
    "  - Only considers lines that look like Futon1 :entity/upsert of"
    "    :arxana/media-lyrics and contain a :source string."
    "  - Skips any log lines containing #= (reader-eval forms)."]))

(defn- parse-args
  [args]
  (loop [m {} xs args]
    (if (empty? xs)
      m
      (let [[k v & more] xs]
        (cond
          (or (= k "--help") (= k "-h")) (assoc m :help? true)
          (= k "--in") (recur (assoc m :in v) more)
          (= k "--out") (recur (assoc m :out v) more)
          (= k "--ep") (recur (assoc m :ep v) more)
          (= k "--audio-dir") (recur (assoc m :audio-dir v) more)
          :else (throw (ex-info "unknown arg" {:arg k :args args})))))))

(defn- audio-file?
  [^File f]
  (let [n (some-> (.getName f) str/lower-case)]
    (boolean (and n (.isFile f) (re-find #"\.(mp3|wav|flac|ogg|m4a)$" n)))))

(defn- list-audio-files
  [audio-dir]
  (let [d (io/file audio-dir)]
    (when-not (and (.exists d) (.isDirectory d))
      (throw (ex-info "audio-dir must be a directory" {:audio-dir audio-dir})))
    (->> (file-seq d)
         (filter audio-file?)
         (vec))))

(defn- sha-set-for-audio-dir
  "Compute Arxana quick-hash SHA values for all audio files in audio-dir."
  [{:keys [audio-dir slice-bytes]}]
  (let [files (list-audio-files audio-dir)]
    {:files files
     :sha->file (->> files
                     (map (fn [^File f]
                            (let [path (.getAbsolutePath f)
                                  sha (lyrics-recovery/quick-hash-sha256 path slice-bytes)]
                              [sha f])))
                     (into {}))}))

(defn- looks-like-lyrics-upsert-line?
  "Cheap pre-filter so we only EDN-parse the small subset of lines we care about."
  [^String line]
  (and (string? line)
       (str/includes? line ":type :entity/upsert")
       (str/includes? line ":type :arxana/media-lyrics")
       (str/includes? line ":source")
       (not (str/includes? line "#="))))

(defn- slug-from-lyrics
  [^String lyrics]
  (when (and (string? lyrics) (seq lyrics))
    (let [line1 (some-> lyrics (str/split-lines) first str/trim)]
      (when (and (seq line1) (re-matches #"[a-z0-9][a-z0-9-]{1,80}" line1))
        line1))))

(defn- title-from-name
  [name]
  (let [n (some-> name str str/trim)]
    (when (seq n)
      (-> n
          (str/replace #"\s*\(lyrics\)\s*$" "")
          (str/trim)))))

(defn- external-id->sha
  [external-id]
  (when (string? external-id)
    (when-let [[_ sha] (re-matches #"arxana/media-lyrics/misc/([0-9a-f]{64})" (str/trim external-id))]
      sha)))

(defn- entity->track
  [{:keys [entity ts]}]
  (let [lyrics (some-> (:source entity) str)
        sha (or (some-> (:media/sha256 entity) str/trim not-empty)
                (external-id->sha (:external-id entity)))
        name (:name entity)
        title (or (title-from-name name)
                  (some-> sha (subs 0 12)))
        slug (or (some-> (:slug entity) str/trim not-empty) ; just in case
                 (slug-from-lyrics lyrics))]
    {:sha sha
     :title title
     :slug slug
     :lyrics lyrics
     :ts ts
     :external-id (:external-id entity)
     :name name}))

(defn- read-lyrics-upserts
  "Return a map sha -> best-track (latest ts) from an ednlog file."
  [{:keys [in-path]}]
  (when-not (and (string? in-path) (seq (str/trim in-path)))
    (throw (ex-info "in-path required" {:in-path in-path})))
  (let [f (io/file in-path)]
    (when-not (and (.exists f) (.isFile f) (.canRead f))
      (throw (ex-info "unreadable in-path" {:in-path in-path})))
    (with-open [r (io/reader f)]
      (reduce
       (fn [acc line]
         (if-not (looks-like-lyrics-upsert-line? line)
           acc
           (try
             (let [m (clojure.edn/read-string {:readers *data-readers*} line)
                   ts (or (:commit/ts m) (:attempt/ts m) (:ts m))
                   entity (get-in m [:event :entity])
                   track (when (map? entity) (entity->track {:entity entity :ts ts}))
                   sha (:sha track)]
               (if (and (seq sha) (seq (:lyrics track)))
                 (let [prev (get acc sha)]
                   (if (and prev (number? (:ts prev)) (number? ts) (> (:ts prev) ts))
                     acc
                     (assoc acc sha track)))
                 acc))
             (catch Exception _
               ;; If parsing fails, skip: this tool is intentionally conservative.
               acc))))
       {}
       (line-seq r)))))

(defn extract!
  "Extract a lyrics recovery JSON for a given audio directory.

  Inputs:
  - in-path (ednlog path)
  - audio-dir (directory with audio)
  - out-path (json to write)
  - ep (string, optional)
  - slice-bytes (optional)"
  [{:keys [in-path audio-dir out-path ep slice-bytes]}]
  (when-not (and (string? audio-dir) (seq (str/trim audio-dir)))
    (throw (ex-info "audio-dir required" {:audio-dir audio-dir})))
  (when-not (and (string? out-path) (seq (str/trim out-path)))
    (throw (ex-info "out-path required" {:out-path out-path})))
  (let [{:keys [sha->file]} (sha-set-for-audio-dir {:audio-dir audio-dir :slice-bytes slice-bytes})
        sha-set (set (keys sha->file))
        all (read-lyrics-upserts {:in-path in-path})
        tracks (->> sha-set
                    (keep (fn [sha]
                            (when-let [t (get all sha)]
                              {:title (:title t)
                               :slug (:slug t)
                               :lyrics (:lyrics t)
                               ;; Keep some provenance for manual inspection.
                               :sha sha
                               :external-id (:external-id t)
                               :futon1/ts (:ts t)})))
                    (sort-by :title)
                    (vec))
        payload {:ep (or ep (some-> (io/file audio-dir) .getName))
                 :source (str "futon1:" (or in-path ""))
                 :tracks tracks}
        json-str (json/encode payload {:pretty true :escape-non-ascii true})]
    (spit out-path json-str)
    {:ok? true
     :in-path in-path
     :out-path out-path
     :audio-dir audio-dir
     :counts {:audio-shas (count sha-set)
              :lyrics-found (count all)
              :tracks-written (count tracks)}
     :sample (mapv #(select-keys % [:title :slug :sha]) (take 3 tracks))}))

(defn -main
  [& args]
  (let [{:keys [help? in out ep audio-dir]} (parse-args args)]
    (when help?
      (println (usage))
      (System/exit 0))
    (let [in (or in "/home/joe/code/futon1/data/default/logs/write-commits.ednlog")
          out (or out (str "/home/joe/code/storage/" (or ep (some-> (io/file audio-dir) .getName)) "-lyrics-data-recovery.extracted.json"))]
      (when-not (and (string? audio-dir) (seq (str/trim audio-dir)))
        (binding [*out* *err*]
          (println (usage)))
        (System/exit 2))
      (let [result (extract! {:in-path in
                              :audio-dir audio-dir
                              :out-path out
                              :ep ep})]
        (println (pr-str result))))))

