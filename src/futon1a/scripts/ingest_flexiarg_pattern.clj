(ns futon1a.scripts.ingest-flexiarg-pattern
  "Ingest one or more .flexiarg patterns into futon1a as a pattern entity
   plus one clause entity per canonical component, linked by typed
   :pattern/has-context / :pattern/has-if / :pattern/has-however /
   :pattern/has-then / :pattern/has-because relations (the central five
   facets), with optional :pattern/has-conclusion / :pattern/has-next-steps
   for the framing pair.

   Source of truth for clause structure: futon.flexiarg.projection
   (the canonical parser landed by P-1 of M-pattern-retrieval-calibration).

   Writes through the futon1a HTTP API (POST /api/alpha/entity, POST
   /api/alpha/relation) so the running futon1a server's L4..L0 pipeline
   validates each write — no local XTDB node opened, no server restart.

   Usage:
     clojure -M -m futon1a.scripts.ingest-flexiarg-pattern \\
       <abs-path-to-flexiarg> [<abs-path-to-flexiarg> ...]

   Env:
     FUTON1A_BASE_URL    default http://localhost:7071
     FUTON1A_PENHOLDER   default api"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [futon.flexiarg.projection :as projection]
            [org.httpkit.client :as http]))

(def ^:private central-five [:context :if :however :then :because])
(def ^:private framing-pair [:conclusion :next-steps])

(defn- env-or [k default]
  (or (some-> (System/getenv k) str/trim not-empty) default))

(defn- post-json! [base path penholder body]
  (let [url (str (str/replace base #"/+$" "") path)
        {:keys [status body error]}
        @(http/post url
                    {:headers {"Content-Type" "application/json"
                               "x-penholder" penholder}
                     :body (json/encode body)
                     :timeout 5000})]
    (when error
      (throw (ex-info "HTTP error" {:url url :error error})))
    (let [parsed (try (json/parse-string body true) (catch Throwable _ nil))]
      (when-not (and (integer? status) (<= 200 status) (< status 300))
        (throw (ex-info (str "non-2xx from " url)
                        {:status status
                         :body parsed
                         :request body})))
      parsed)))

(def ^:private facet-aliases
  "Map facet → set of source clause name-keys that count as that facet.
   Mirrors `futon.flexiarg.projection/conclusion-aliases`."
  {:conclusion #{"conclusion" "claim" "summary" "instantiated-by"}})

(defn- clause-text-for [packet facet]
  (let [aliases (or (get facet-aliases facet) #{(name facet)})]
    (some (fn [c] (when (contains? aliases (:name-key c)) (:text c)))
          (:pattern/clauses packet))))

(defn- pattern-entity-payload [packet]
  (let [pid (:pattern/id packet)
        title (:pattern/title packet)]
    {:name pid
     :type "pattern/library"
     :external-id pid
     :source (or title pid)}))

(defn- clause-entity-payload [pattern-id facet text]
  (let [name (str pattern-id "/" (name facet))]
    {:name name
     :type "pattern/clause"
     :external-id name
     :source (or text "")}))

(defn- relation-payload [src-id dst-id facet]
  (let [rel-type (str ":pattern/has-" (name facet))]
    {:type rel-type
     :src src-id
     :dst dst-id
     :provenance {:note rel-type
                  :source "ingest-flexiarg-pattern"}}))

(defn ingest-pattern!
  "POST one pattern entity + clause entities + has-* relations.

   Returns {:pattern <entity> :clauses {facet <entity>} :relations [...]}."
  [{:keys [base-url penholder packet]}]
  (when-not (= :ok (:pattern/status packet))
    (throw (ex-info "packet has error status; aborting"
                    {:status (:pattern/status packet)
                     :error (:pattern/error packet)})))
  (let [pid (:pattern/id packet)
        pattern-resp (post-json! base-url "/api/alpha/entity" penholder
                                 (pattern-entity-payload packet))
        pattern-entity (:entity pattern-resp)
        all-facets (concat central-five framing-pair)
        clause-results (reduce
                        (fn [acc facet]
                          (if-let [text (clause-text-for packet facet)]
                            (let [resp (post-json! base-url "/api/alpha/entity" penholder
                                                   (clause-entity-payload pid facet text))]
                              (assoc acc facet (:entity resp)))
                            acc))
                        {}
                        all-facets)
        relation-results (mapv
                          (fn [[facet clause-entity]]
                            (let [resp (post-json! base-url "/api/alpha/relation" penholder
                                                   (relation-payload (:id pattern-entity)
                                                                     (:id clause-entity)
                                                                     facet))]
                              {:facet facet
                               :relation (:relation resp)
                               :tx-id (:tx-id resp)}))
                          clause-results)]
    {:pattern pattern-entity
     :clauses clause-results
     :relations relation-results}))

(defn- expand-paths
  "If `path` is a directory, walk it for .flexiarg/.multiarg files;
   otherwise return [path]."
  [path]
  (let [f (clojure.java.io/file path)]
    (cond
      (and (.isDirectory f))
      (->> (file-seq f)
           (filter #(.isFile %))
           (filter (fn [g]
                     (let [n (.getName g)]
                       (or (.endsWith n ".flexiarg")
                           (.endsWith n ".multiarg")))))
           (sort-by #(.getCanonicalPath %))
           (mapv #(.getCanonicalPath %)))
      :else [path])))

(defn -main
  [& args]
  (when (empty? args)
    (println "Usage: clojure -M -m futon1a.scripts.ingest-flexiarg-pattern <path-or-dir> ...")
    (System/exit 1))
  (let [base-url (env-or "FUTON1A_BASE_URL" "http://localhost:7071")
        penholder (env-or "FUTON1A_PENHOLDER" "api")
        paths (mapcat expand-paths args)
        results (atom {:ok 0 :errors [] :missing-conclusion []})]
    (println (format "Base: %s   Penholder: %s   Targets: %d files"
                     base-url penholder (count paths)))
    (doseq [path paths]
      (try
        (let [packets (projection/parse-file path)]
          (doseq [packet packets]
            (try
              (let [pid (:pattern/id packet)
                    result (ingest-pattern! {:base-url base-url
                                             :penholder penholder
                                             :packet packet})
                    facets (set (keys (:clauses result)))]
                (swap! results update :ok inc)
                (when-not (contains? facets :conclusion)
                  (swap! results update :missing-conclusion conj pid))
                (println (format "  ok %-50s  facets=%s"
                                 pid
                                 (str/join "," (sort (map name facets))))))
              (catch Throwable ex
                (swap! results update :errors conj
                       {:path path
                        :pattern (:pattern/id packet)
                        :error (.getMessage ex)})
                (println (format "  ERR %-50s  %s"
                                 (or (:pattern/id packet) path)
                                 (.getMessage ex)))))))
        (catch Throwable ex
          (swap! results update :errors conj {:path path :error (.getMessage ex)})
          (println (format "  ERR %-50s  %s" path (.getMessage ex))))))
    (let [{:keys [ok errors missing-conclusion]} @results]
      (println (format "\n--- summary ---"))
      (println (format "Ingested:           %d" ok))
      (println (format "Errors:             %d" (count errors)))
      (println (format "Missing conclusion: %d" (count missing-conclusion)))
      (when (seq missing-conclusion)
        (println "  missing-conclusion patterns:")
        (doseq [p (take 20 missing-conclusion)] (println (format "    %s" p))))
      (when (seq errors)
        (println "  first 5 errors:")
        (doseq [e (take 5 errors)] (println (format "    %s" e)))))))
