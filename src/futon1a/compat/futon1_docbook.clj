(ns futon1a.compat.futon1-docbook
  "Compatibility docbook storage/query semantics for Futon1/Futon4 HTTP surfaces."
  (:require [clojure.string :as str]
            [xtdb.api :as xtdb])
  (:import (java.math BigInteger)
           (java.security MessageDigest)))

(def ^:private heading-required
  [:doc/id :doc/book :doc/title :doc/outline_path :doc/path_string :doc/level])

(def ^:private entry-required
  [:doc/id :doc/entry-id :doc/book])

(def ^:private toc-heading-keys
  [:doc/id :doc/path_string :doc/outline_path :doc/level :doc/title])

(def ^:private toc-latest-keys
  [:doc/entry-id :doc/timestamp :doc/version :doc/status])

(defn- nonblank-str
  [v]
  (let [s (some-> v str str/trim)]
    (when (seq s) s)))

(defn normalize-book
  [book]
  (or (nonblank-str book) "futon4"))

(defn- parse-long-safe
  [v]
  (cond
    (integer? v) (long v)
    (string? v) (try (Long/parseLong (str/trim v))
                     (catch Exception _ nil))
    :else nil))

(defn- normalize-status
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [s (nonblank-str v)]
                  (if s (keyword s) :active))
    :else :active))

(defn- normalize-outline
  [v]
  (cond
    (vector? v) v
    (sequential? v) (vec v)
    (string? v) (->> (str/split v #"\s*/\s*")
                     (remove str/blank?)
                     vec)
    :else nil))

(defn- blank->nil
  [v]
  (if (and (string? v) (str/blank? v)) nil v))

(defn- missing?
  [v]
  (cond
    (nil? v) true
    (string? v) (str/blank? v)
    (sequential? v) (empty? v)
    :else false))

(defn- missing-fields
  [m fields]
  (->> fields
       (filter #(missing? (get m %)))
       vec))

(defn- heading-error
  [heading]
  (when-let [missing (seq (missing-fields heading heading-required))]
    {:issue :missing-fields
     :missing (vec missing)}))

(defn- entry-error
  [entry]
  (let [missing (seq (missing-fields entry entry-required))
        status (:doc/status entry)
        body (:doc/body entry)]
    (cond
      missing {:issue :missing-fields
               :missing (vec missing)}
      (and (not= :removed status) (missing? body))
      {:issue :missing-body}
      :else nil)))

(defn- sha1
  [^String value]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (.update digest (.getBytes (or value "") "UTF-8"))
    (format "%040x" (BigInteger. 1 (.digest digest)))))

(defn- short-hash
  [value]
  (subs (sha1 value) 0 12))

(defn derive-doc-id
  "Derive a doc-id from book + outline path using the historical toc hash rule."
  [book outline-path]
  (when (seq outline-path)
    (let [outline-key (str/join "/" outline-path)]
      (str book "-" (short-hash (format "%s::%s" book outline-key))))))

(defn- normalize-entry-payload
  [book payload]
  (let [book (normalize-book book)
        outline-raw (or (:doc/outline_path payload)
                        (:outline_path payload))
        path-str-raw (or (:doc/path_string payload)
                         (:path_string payload))
        outline (or (normalize-outline outline-raw)
                    (normalize-outline path-str-raw))
        path-str (or path-str-raw
                     (when (seq outline) (str/join " / " outline)))
        title (or (:doc/title payload)
                  (:title payload)
                  (when (seq outline) (last outline)))
        level (or (parse-long-safe (:doc/level payload))
                  (parse-long-safe (:level payload))
                  (some-> outline count))
        doc-id (or (:doc/id payload)
                   (:doc_id payload)
                   (:doc-id payload)
                   (derive-doc-id book outline))]
    (cond-> (assoc payload :doc/book book)
      doc-id (assoc :doc/id doc-id)
      outline (assoc :doc/outline_path outline)
      path-str (assoc :doc/path_string path-str)
      title (assoc :doc/title title)
      level (assoc :doc/level level))))

(defn- heading-doc
  [payload]
  (let [doc-id (or (:doc/id payload) (:doc_id payload))
        outline (normalize-outline (or (:doc/outline_path payload)
                                       (:outline_path payload)))
        path-str (or (:doc/path_string payload) (:path_string payload))
        level (or (parse-long-safe (:doc/level payload))
                  (parse-long-safe (:level payload))
                  (some-> outline count))
        supersedes (or (:doc/supersedes payload) (:supersedes payload))
        title (or (:doc/title payload) (:title payload))]
    (cond-> {:xt/id doc-id
             :doc/id doc-id}
      (or (:doc/book payload) (:book payload) (:book_id payload))
      (assoc :doc/book (or (:doc/book payload) (:book payload) (:book_id payload)))
      title (assoc :doc/title title)
      outline (assoc :doc/outline_path outline)
      path-str (assoc :doc/path_string path-str)
      level (assoc :doc/level level)
      supersedes (assoc :doc/supersedes supersedes))))

(defn- entry-doc
  [book payload]
  (let [doc-id (or (:doc/id payload) (:doc_id payload))
        entry-id (or (:doc/entry-id payload)
                     (:doc_entry_id payload)
                     (:doc/entry_id payload)
                     (:entry_id payload)
                     (:entry-id payload)
                     (:run_id payload)
                     (:run/id payload))
        body (blank->nil (or (:doc/body payload)
                             (:doc/context payload)
                             (:body payload)
                             (:context payload)
                             (:agent_summary payload)
                             (:summary payload)))]
    (when (and doc-id entry-id)
      (let [status (normalize-status (or (:doc/status payload) (:status payload)))]
        (cond-> {:xt/id entry-id
                 :doc/id doc-id
                 :doc/entry-id entry-id
                 :doc/book book
                 :doc/version (or (:version payload) (:doc/version payload))
                 :doc/timestamp (or (:timestamp payload) (:doc/timestamp payload))
                 :doc/status status}
          body (assoc :doc/body body)
          (:replaces payload) (assoc :doc/replaces (:replaces payload))
          (:doc/replaces payload) (assoc :doc/replaces (:doc/replaces payload))
          (:merges payload) (assoc :doc/merges (:merges payload))
          (:doc/merges payload) (assoc :doc/merges (:doc/merges payload))
          (:links_to payload) (assoc :doc/links-to (:links_to payload))
          (:doc/links-to payload) (assoc :doc/links-to (:doc/links-to payload))
          (:files_touched payload) (assoc :doc/files (:files_touched payload))
          (:doc/files payload) (assoc :doc/files (:doc/files payload))
          (:doc/commit payload) (assoc :doc/commit (:doc/commit payload))
          (:commits payload) (assoc :doc/commit (first (:commits payload)))
          (:scope payload) (assoc :doc/scope (:scope payload))
          (:doc/scope payload) (assoc :doc/scope (:doc/scope payload))
          (:tracker_refs payload) (assoc :doc/tracker-refs (:tracker_refs payload))
          (:doc/tracker-refs payload) (assoc :doc/tracker-refs (:doc/tracker-refs payload))
          (:doc/delta payload) (assoc :doc/delta (:doc/delta payload))
          (:delta payload) (assoc :doc/delta (:delta payload))
          (:doc/verification payload) (assoc :doc/verification (:doc/verification payload))
          (:verification payload) (assoc :doc/verification (:verification payload))
          (:doc/toc-version payload) (assoc :doc/toc-version (:doc/toc-version payload))
          (:notes payload) (assoc :doc/notes (:notes payload)))))))

(defn prepare-upsert-entry
  "Normalize and validate a single docbook entry payload.

   Returns:
   - success: {:ok? true :book ... :doc-id ... :entry-id ... :heading ... :entry ... :tx-ops [...]}
   - reject:  {:ok? false ...}"
  [book payload]
  (let [book (normalize-book book)
        payload (normalize-entry-payload book payload)
        heading (heading-doc payload)
        entry (entry-doc book payload)
        heading-err (when heading (heading-error heading))
        entry-err (when entry (entry-error entry))]
    (cond
      (not (and heading entry))
      {:ok? false
       :error "doc_id + entry_id required (or outline_path to derive doc_id)"
       :book book}

      heading-err
      {:ok? false
       :error "Invalid docbook heading payload"
       :book book
       :doc-id (:doc/id heading)
       :details heading-err}

      entry-err
      {:ok? false
       :error "Invalid docbook entry payload"
       :book book
       :doc-id (:doc/id entry)
       :entry-id (:doc/entry-id entry)
       :details entry-err}

      :else
      {:ok? true
       :book book
       :doc-id (:doc/id heading)
       :entry-id (:doc/entry-id entry)
       :heading heading
       :entry entry
       :tx-ops [[:xtdb.api/put heading]
                [:xtdb.api/put entry]]})))

(defn prepare-upsert-entries
  "Normalize and validate a batch of docbook entry payloads.
   Mirrors Futon1 behavior: valid entries are accepted even when some fail."
  [book entries]
  (let [book (normalize-book book)
        prepared (mapv (fn [payload]
                         (let [payload (normalize-entry-payload book payload)
                               heading (heading-doc payload)
                               entry (entry-doc book payload)
                               heading-err (when heading (heading-error heading))
                               entry-err (when entry (entry-error entry))]
                           {:payload payload
                            :heading heading
                            :entry entry
                            :heading-err heading-err
                            :entry-err entry-err
                            :ok? (and heading entry (not heading-err) (not entry-err))}))
                       entries)
        errors (->> prepared
                    (remove :ok?)
                    (mapv (fn [{:keys [payload heading entry heading-err entry-err]}]
                            {:error "Invalid docbook payload"
                             :doc-id (or (:doc/id heading) (:doc/id entry))
                             :entry-id (:doc/entry-id entry)
                             :heading-error heading-err
                             :entry-error entry-err
                             :payload payload})))
        tx-ops (->> prepared
                    (filter :ok?)
                    (mapcat (fn [{:keys [heading entry]}]
                              [[:xtdb.api/put heading]
                               [:xtdb.api/put entry]]))
                    vec)]
    {:ok? (empty? errors)
     :book book
     :count (count entries)
     :accepted (- (count entries) (count errors))
     :errors errors
     :tx-ops tx-ops}))

(defn- normalize-entry
  [entry]
  (cond-> entry
    (and (:doc/context entry) (not (:doc/body entry)))
    (-> (assoc :doc/body (:doc/context entry))
        (dissoc :doc/context))
    (:doc/context entry) (dissoc :doc/context)))

(defn- latest-entry
  [entries]
  (->> entries
       (remove #(= :removed (:doc/status %)))
       (sort-by (juxt #(or (:doc/timestamp %) "")
                      #(or (:doc/version %) ""))
                #(compare %2 %1))
       first))

(defn- heading-sort-key
  [heading]
  [(or (:doc/path_string heading) "")
   (or (:doc/title heading) "")
   (or (:doc/id heading) "")])

(defn- default-heading-order
  [headings]
  (->> headings
       (sort-by heading-sort-key)
       (map :doc/id)
       vec))

(defn- merge-order
  [preferred fallback]
  (let [preferred* (vec (distinct preferred))
        seen (set preferred*)]
    (vec (concat preferred* (remove seen fallback)))))

(defn- toc-order-id
  [book]
  (str "docbook-toc-order::" book))

(defn- fetch-toc-order
  [db book]
  (some-> (xtdb/entity db (toc-order-id book))
          :doc/toc-order))

(defn- headings-for-book
  [db book]
  (let [docs (->> (xtdb/q db '{:find [(pull h [*])]
                           :in [book]
                           :where [[h :doc/book book]
                                   [h :doc/id doc-id]]}
                         book)
                  (map first))]
    (->> docs
         (remove :doc/entry-id)
         (group-by :doc/id)
         (vals)
         (map (fn [coll]
                (->> coll
                     (sort-by (fn [d] (str (or (:xt/id d) ""))))
                     first)))
         vec)))

(defn- ordered-headings
  [db book]
  (let [headings (headings-for-book db book)
        default-order (default-heading-order headings)
        stored-order (fetch-toc-order db book)
        ordered-ids (if (seq stored-order)
                      (merge-order (filter (set default-order) stored-order)
                                   default-order)
                      default-order)
        by-id (group-by :doc/id headings)]
    (->> ordered-ids
         (mapcat #(get by-id %))
         vec)))

(defn- entries-for-doc
  [db book doc-id]
  (->> (xtdb/q db '{:find [(pull e [*])]
                  :in [book doc-id]
                  :where [[e :doc/book book]
                          [e :doc/id doc-id]
                          [e :doc/entry-id entry-id]]}
                book doc-id)
       (map first)))

(defn- heading->with-latest
  [db book heading]
  (let [entries (->> (entries-for-doc db book (:doc/id heading))
                     (map normalize-entry)
                     vec)
        latest (latest-entry entries)]
    (assoc heading
           :doc/entries (->> entries
                             (sort-by #(or (:doc/timestamp %) "")
                                      #(compare %2 %1))
                             vec)
           :doc/latest latest)))

(defn contents
  [node book]
  (let [book (normalize-book book)
        db (xtdb/db node)
        headings (->> (ordered-headings db book)
                      (map #(heading->with-latest db book %))
                      vec)]
    {:book book
     :headings headings}))

(defn toc
  [node book]
  (let [book (normalize-book book)
        db (xtdb/db node)
        headings (ordered-headings db book)]
    {:book book
     :headings (mapv (fn [heading]
                       (let [entries (entries-for-doc db book (:doc/id heading))
                             active (remove #(= :removed (:doc/status %)) entries)
                             latest (latest-entry entries)]
                         (cond-> (select-keys heading toc-heading-keys)
                           (seq active) (assoc :doc/entry-count (count active))
                           latest (assoc :doc/latest (select-keys latest toc-latest-keys)))))
                     headings)}))

(defn heading+entries
  [node book doc-id]
  (let [book (normalize-book book)
        db (xtdb/db node)
        docs (->> (xtdb/q db '{:find [(pull e [*])]
                        :in [book doc-id]
                        :where [[e :doc/book book]
                                [e :doc/id doc-id]]}
                      book doc-id)
                  (map first)
                  vec)
        heading (->> docs
                     (remove :doc/entry-id)
                     (sort-by (fn [d] (str (or (:xt/id d) ""))))
                     first)
        entries (->> docs
                     (filter :doc/entry-id)
                     (map normalize-entry)
                     vec)
        latest (latest-entry entries)]
    (when heading
      (assoc heading
             :doc/entries (->> entries
                               (sort-by #(or (:doc/timestamp %) "")
                                        #(compare %2 %1))
                               vec)
             :doc/latest latest))))

(defn recent-entries
  ([node book] (recent-entries node book nil))
  ([node book limit]
   (let [book (normalize-book book)
         db (xtdb/db node)
         limit (or (parse-long-safe limit) 20)
         limit (if (pos? limit) limit 20)
         headings (headings-for-book db book)
         headings-by-id (into {} (map (juxt :doc/id identity) headings))
         entries (->> (xtdb/q db '{:find [(pull e [*])]
                         :in [book]
                         :where [[e :doc/book book]
                                 [e :doc/entry-id entry-id]]}
                       book)
                      (map first)
                      (map normalize-entry)
                      (remove #(= :removed (:doc/status %)))
                      (map (fn [e]
                             (let [heading (get headings-by-id (:doc/id e))]
                               (cond-> e
                                 heading (assoc :doc/heading (select-keys heading
                                                                         [:doc/id :doc/path_string :doc/book]))))))
                      (sort-by #(or (:doc/timestamp %) "")
                               #(compare %2 %1))
                      (take limit)
                      vec)]
     {:book book
      :entries entries})))

(defn prepare-update-toc-order
  [node book {:keys [order source timestamp]}]
  (let [book (normalize-book book)
        db (xtdb/db node)
        headings (headings-for-book db book)
        default-order (default-heading-order headings)
        heading-ids (set default-order)
        order* (->> order
                    (map nonblank-str)
                    (remove nil?))
        known-order (filter heading-ids order*)
        ignored (remove heading-ids order*)
        stored-order* (->> (or (fetch-toc-order db book) [])
                           (map nonblank-str)
                           (remove nil?))
        existing-order (if (seq stored-order*)
                         (merge-order (filter heading-ids stored-order*)
                                      default-order)
                         default-order)
        final-order (->> (merge-order known-order existing-order)
                         (remove nil?)
                         vec)
        doc (cond-> {:xt/id (toc-order-id book)
                     :doc/book book
                     :doc/toc-order final-order}
              (nonblank-str source) (assoc :doc/toc-source (nonblank-str source))
              (nonblank-str timestamp) (assoc :doc/toc-updated-at (nonblank-str timestamp)))]
    {:tx-ops [[:xtdb.api/put doc]]
     :response (cond-> {:status "ok"
                        :book book
                        :count (count final-order)}
                 (seq ignored) (assoc :ignored-doc-ids (vec (distinct ignored))))}))

(defn- docs-for
  [db book doc-id]
  (->> (xtdb/q db '{:find [(pull e [*])]
                  :in [book doc-id]
                  :where [[e :doc/book book]
                          [e :doc/id doc-id]]}
                book doc-id)
       (map first)))

(defn- doc-xt-ids
  [docs]
  (->> docs
       (keep :xt/id)
       distinct
       vec))

(defn plan-delete-doc
  [node book doc-id]
  (let [book (normalize-book book)
        db (xtdb/db node)
        ids (doc-xt-ids (docs-for db book doc-id))]
    {:book book
     :doc-id doc-id
     :deleted (count ids)
     :tx-ops (mapv (fn [id] [:xtdb.api/delete id]) ids)}))

(defn plan-delete-toc
  [node book doc-id {:keys [cascade?]}]
  (let [book (normalize-book book)
        db (xtdb/db node)
        docs (docs-for db book doc-id)
        entries (filter :doc/entry-id docs)
        headings (remove :doc/entry-id docs)
        ids (doc-xt-ids (if cascade?
                          (concat headings entries)
                          headings))]
    {:book book
     :doc-id doc-id
     :deleted (count ids)
     :cascade? (boolean cascade?)
     :tx-ops (mapv (fn [id] [:xtdb.api/delete id]) ids)}))
