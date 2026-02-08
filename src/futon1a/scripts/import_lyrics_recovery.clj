(ns futon1a.scripts.import-lyrics-recovery
  "Import lyrics from an out-of-band recovery JSON plus local audio files.

  This is intentionally an explicit operational tool (no hidden magic):
  - It pairs JSON track entries with audio files in a directory (page-N-*.mp3).
  - It computes Arxana's 'quick hash' (size + head/tail slices) to match the
    identifiers Arxana uses for misc audio.
  - It writes track + lyrics entities and :media/lyrics relations into futon1a."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [xtdb.api :as xtdb])
  (:import (java.io File RandomAccessFile ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def ^:private default-slice-bytes 8192)

(defn- hex
  ^String [^bytes bs]
  (let [sb (StringBuilder.)]
    (dotimes [i (alength bs)]
      (.append sb (format "%02x" (bit-and (aget bs i) 0xff))))
    (str sb)))

(defn- sha256-bytes
  ^bytes [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.digest md bs)))

(defn quick-hash-sha256
  "Replicate `arxana-media--file-quick-hash` from futon4/dev/arxana-media.el.

  Hash is SHA-256 over:
  - ASCII bytes of \"<size>:\"
  - head slice (0..slice) or whole file if <= 2*slice
  - tail slice (size-slice..size) only when > 2*slice

  Returns lowercase hex string."
  ([path] (quick-hash-sha256 path default-slice-bytes))
  ([path slice-bytes]
   (when-not (and (string? path) (seq (str/trim path)))
     (throw (ex-info "path required" {:path path})))
   (let [f (io/file path)]
     (when-not (and (.exists f) (.isFile f) (.canRead f))
       (throw (ex-info "unreadable file" {:path path})))
     (let [size (.length ^File f)
           slice (long (max 1 (or slice-bytes default-slice-bytes)))
           prefix (.getBytes (str size ":") StandardCharsets/UTF_8)
           head-len (if (<= size (* 2 slice)) (int size) (int slice))
           tail? (> size (* 2 slice))
           tail-len (if tail? (int slice) 0)
           baos (ByteArrayOutputStream.)]
       (.write baos prefix)
       (with-open [raf (RandomAccessFile. f "r")]
         (let [head (byte-array head-len)]
           (.seek raf 0)
           (.readFully raf head)
           (.write baos head))
         (when tail?
           (let [tail (byte-array tail-len)]
             (.seek raf (- size slice))
             (.readFully raf tail)
             (.write baos tail))))
       (hex (sha256-bytes (.toByteArray baos)))))))

(defn- page-n
  [^String filename]
  (when-let [m (re-matches #"(?i)page-(\\d+)-.*\\.(mp3|wav|flac|ogg|m4a)$" filename)]
    (Long/parseLong (nth m 1))))

(defn- audio-file?
  [^File f]
  (let [n (some-> (.getName f) str/lower-case)]
    (boolean (and n (re-find #"\.(mp3|wav|flac|ogg|m4a)$" n)))))

(defn- base-name
  [^File f]
  (let [n (.getName f)
        i (.lastIndexOf n ".")]
    (if (pos? i) (subs n 0 i) n)))

(defn- slugify
  [s]
  (-> (or s "")
      (str/lower-case)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")
      (str/replace #"-+" "-")))

(defn- normalize-for-match
  [s]
  (-> (or s "")
      (str/lower-case)
      (str/replace #"[^a-z0-9]+" " ")
      (str/replace #"\s+" " ")
      (str/trim)))

(defn- select-file
  "Select an audio file for a track map.

  Priority:
  1) base-name == :slug
  2) base-name == (slugify :title)
  3) content match: unique base-name substring in lyrics"
  [base->file {:keys [slug title lyrics]}]
  (let [slug (some-> slug str str/trim)
        title (some-> title str str/trim)
        lyrics (some-> lyrics str)
        by-slug (when (seq slug) (get base->file slug))
        by-title (when (seq title) (get base->file (slugify title)))
        lyr-n (normalize-for-match lyrics)]
    (cond
      by-slug {:file by-slug :reason :slug}
      by-title {:file by-title :reason :title}
      (seq lyr-n)
      (let [hits (->> base->file
                      (keep (fn [[base f]]
                              (let [b (normalize-for-match base)]
                                (when (and (seq b) (str/includes? lyr-n b))
                                  {:file f :reason :lyrics :match b}))))
                      (vec))]
        (cond
          (= 1 (count hits)) (first hits)
          :else {:error :no-unique-match
                 :candidates (mapv (fn [{:keys [file match]}]
                                     {:file (.getName ^File file) :match match})
                                   hits)}))
      :else {:error :no-match})))

(defn- list-audio-files
  [audio-dir]
  (let [d (io/file audio-dir)]
    (when-not (and (.exists d) (.isDirectory d))
      (throw (ex-info "audio-dir must be a directory" {:audio-dir audio-dir})))
    (->> (file-seq d)
         (filter #(.isFile ^File %))
         (filter audio-file?)
         (vec))))

(defn- normalize-title [s]
  (some-> s str (str/trim)))

(defn- track-doc
  [{:keys [track-id title sha path]}]
  {:xt/id track-id
   :entity/id track-id
   :entity/type :arxana/media-track
   :entity/name (or title track-id)
   :entity/external-id track-id
   :media/sha256 sha
   :media/path path})

(defn- lyrics-doc
  [{:keys [lyrics-id title lyrics sha]}]
  {:xt/id lyrics-id
   :entity/id lyrics-id
   :entity/type :arxana/media-lyrics
   :entity/name (format "%s (lyrics)" (or title "track"))
   :entity/external-id lyrics-id
   :entity/source lyrics
   :media/sha256 sha})

(defn- rel-doc
  [{:keys [rel-id track-id lyrics-id]}]
  {:xt/id rel-id
   :relation/id rel-id
   :relation/type :media/lyrics
   :relation/from track-id
   :relation/to lyrics-id
   :relation/src track-id
   :relation/dst lyrics-id
   :relation/provenance {:note ":media/lyrics"}
   :relation/confidence 1.0})

(defn import!
  "Import lyrics recovery JSON into futon1a.

  Inputs:
  - store, penholder, allowed-penholders
  - json-path (recovery json with {:tracks [{:title :lyrics} ...]})
  - audio-dir (directory containing page-N-*.mp3 etc)
  - slice-bytes (optional, default 8192)
  - dry-run? (optional)
  "
  [{:keys [store penholder allowed-penholders json-path audio-dir slice-bytes dry-run?]}]
  (when-not store
    (throw (ex-info "store required" {:store store})))
  (when-not (and (string? penholder) (seq (str/trim penholder)))
    (throw (ex-info "penholder required" {:penholder penholder})))
  (when-not (and (string? json-path) (seq (str/trim json-path)))
    (throw (ex-info "json-path required" {:json-path json-path})))
  (when-not (and (string? audio-dir) (seq (str/trim audio-dir)))
    (throw (ex-info "audio-dir required" {:audio-dir audio-dir})))
  (let [payload (json/parse-string (slurp json-path) true)
        tracks (:tracks payload)
        _ (when-not (sequential? tracks)
            (throw (ex-info "recovery json missing :tracks vector" {:json-path json-path :keys (keys payload)})))
        files (list-audio-files audio-dir)
        page-files (->> files
                        (filter (fn [^File f] (some? (page-n (.getName f)))))
                        (sort-by (fn [^File f] (page-n (.getName f))))
                        (vec))
        base->file (->> files
                        (map (fn [^File f] [(base-name f) f]))
                        (into {}))
        ;; Two modes:
        ;; - page-N pairing when available and counts match (pma-ep1 style)
        ;; - slug/title/lyrics matching otherwise (pma-ep2 style)
        mode (cond
               (and (= (count tracks) (count page-files)) (pos? (count page-files))) :page
               :else :match)
        items (case mode
                :page
                (mapv (fn [t ^File f]
                        (let [title (normalize-title (:title t))
                              lyrics (some-> (:lyrics t) str)
                              path (.getAbsolutePath f)
                              sha (quick-hash-sha256 path slice-bytes)
                              track-id (format "arxana/media/misc/%s" sha)
                              lyrics-id (format "arxana/media-lyrics/misc/%s" sha)
                              rel-id (format "rel|%s|media/lyrics|%s" track-id lyrics-id)]
                          {:title title
                           :slug (:slug t)
                           :reason :page
                           :file (.getName f)
                           :lyrics lyrics
                           :path path
                           :sha sha
                           :track-id track-id
                           :lyrics-id lyrics-id
                           :rel-id rel-id}))
                      tracks page-files)

                :match
                (let [selected (mapv (fn [t]
                                       (assoc t :selection (select-file base->file t)))
                                     tracks)
                      problems (->> selected
                                    (keep (fn [{:keys [title slug selection]}]
                                            (when (or (:error selection) (nil? (:file selection)))
                                              {:title title :slug slug :selection selection})))
                                    (vec))]
                  (when (seq problems)
                    (throw (ex-info "could not match some tracks to audio files"
                                    {:problems problems
                                     :audio-dir audio-dir
                                     :json-path json-path
                                     :available (vec (sort (keys base->file)))})))
                  (mapv (fn [{:keys [selection] :as t}]
                          (let [^File f (:file selection)
                                title (normalize-title (:title t))
                                lyrics (some-> (:lyrics t) str)
                                path (.getAbsolutePath f)
                                sha (quick-hash-sha256 path slice-bytes)
                                track-id (format "arxana/media/misc/%s" sha)
                                lyrics-id (format "arxana/media-lyrics/misc/%s" sha)
                                rel-id (format "rel|%s|media/lyrics|%s" track-id lyrics-id)]
                            {:title title
                             :slug (:slug t)
                             :reason (:reason selection)
                             :file (.getName f)
                             :lyrics lyrics
                             :path path
                             :sha sha
                             :track-id track-id
                             :lyrics-id lyrics-id
                             :rel-id rel-id}))
                        selected)) )
        docs (->> items
                  (mapcat (fn [{:keys [track-id lyrics-id rel-id title lyrics sha path]}]
                            [(track-doc {:track-id track-id :title title :sha sha :path path})
                             (lyrics-doc {:lyrics-id lyrics-id :title title :lyrics lyrics :sha sha})
                             (rel-doc {:rel-id rel-id :track-id track-id :lyrics-id lyrics-id})]))
                  (vec))
        entities (->> docs
                      (filter :entity/id)
                      (mapv (fn [d] {:entity/id (:entity/id d) :entity/type (:entity/type d)})))
        relations (->> docs
                       (filter :relation/id)
                       (mapv (fn [d] {:relation/id (:relation/id d)
                                      :relation/type (:relation/type d)
                                      :relation/from (:relation/from d)
                                      :relation/to (:relation/to d)})))
        tx-ops (mapv (fn [d] [::xtdb/put d]) docs)
        summary {:ok? true
                 :dry-run? (boolean dry-run?)
                 :counts {:tracks (count tracks)
                          :files (count files)
                          :docs (count docs)
                          :entities (count entities)
                          :relations (count relations)}
                 :mode mode
                 :sample (mapv #(select-keys % [:title :slug :file :reason :track-id :lyrics-id :path]) (take 3 items))}]
    (if dry-run?
      summary
      (merge summary
             (pipeline/run-open-world!
              {:store store
               :penholder penholder
               :allowed-penholders (or allowed-penholders #{})
               :entities entities
               :relations relations
               :require-model? false
               :tx-ops tx-ops
               :claim {:op :import/lyrics-recovery}
               :detail {:json-path json-path
                        :audio-dir audio-dir
                        :tracks (count tracks)}})))))
