(ns futon1a.system
  "Prototype 1 runnable system.

   Owns lifecycle for:
   - XTDB node (embedded, persistent)
   - Ring HTTP server (Jetty)

   This is intentionally small and explicit: the goal is to prove the
   end-to-end restart cycle (I0) without special cases."
  (:require [clojure.string :as str]
            [futon1a.core.xtdb-node :as xtnode]
            [futon1a.http.app :as http-app]
            [ring.adapter.jetty :as jetty]))

(defn xtdb-config
  "Build an embedded XTDB config with explicit on-disk dirs.

   Keys:
   - data-dir (string) base dir

   Layout:
   - <data-dir>/tx-log
   - <data-dir>/doc-store
   - <data-dir>/index-store"
  [{:keys [data-dir]}]
  (when-not (and (string? data-dir) (seq data-dir))
    (throw (ex-info "data-dir required" {:data-dir data-dir})))
  (let [tx-log (str data-dir "/tx-log")
        doc-store (str data-dir "/doc-store")
        index-store (str data-dir "/index-store")
        kv {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store}}]
    {:xtdb/tx-log (assoc-in kv [:kv-store :db-dir] tx-log)
     :xtdb/document-store (assoc-in kv [:kv-store :db-dir] doc-store)
     :xtdb/index-store (assoc-in kv [:kv-store :db-dir] index-store)}))

(defn- jetty-port
  "Return the actual local port Jetty bound to."
  [^org.eclipse.jetty.server.Server server]
  (some-> server
          (.getConnectors)
          (first)
          (.getLocalPort)))

(defn- parse-bool
  "Parse common truthy/falsey env strings. Unset => default."
  [s default]
  (if (nil? s)
    default
    (let [v (-> s str str/trim str/lower-case)]
      (not (contains? #{"0" "false" "no" "off"} v)))))

(defn- normalize-penholders
  [allowed]
  (->> (or allowed #{})
       (keep (fn [v]
               (let [s (some-> v str str/trim)]
                 (when (seq s) s))))
       (set)))

(defn- validate-start-policy!
  [{:keys [allowed-penholders allow-empty-penholders? compat-penholder]}]
  (when (and (empty? allowed-penholders) (not allow-empty-penholders?))
    (throw (ex-info "allowed-penholders required"
                    {:error {:reason :missing-allowed-penholders
                             :context {:hint "Set FUTON1A_ALLOWED_PENHOLDERS (e.g. api,joe)."
                                       :allow-empty-penholders? false}}})))
  (when (and compat-penholder
             (not (contains? allowed-penholders compat-penholder)))
    (throw (ex-info "compat/penholder must be allowed"
                    {:error {:reason :invalid-compat-penholder
                             :context {:compat/penholder compat-penholder
                                       :allowed-penholders (vec (sort allowed-penholders))}}}))))

(defn start!
  "Start the system.

  Inputs:
  - data-dir (string) XTDB persistence directory
  - port (int, optional) default 7071; use 0 for ephemeral
  - allowed-penholders (set of strings)
  - allow-empty-penholders? (boolean, optional) default false
  - expose-internals? (boolean, optional) default false

   Returns a system map with:
   - http/server, http/port, http/handler
   - node, store (only when expose-internals? true)
   - stop! (fn [] => stopped-system)"
  [{:keys [data-dir port static-dir allowed-penholders allowed-expansions allowed-tooling
           allow-empty-penholders? expose-internals?]
    :or {port 7071
         allow-empty-penholders? false
         expose-internals? false}
    :as opts}]
  (let [allowed-penholders (normalize-penholders allowed-penholders)
        compat-penholder (some-> (get opts :compat/penholder) str/trim not-empty)
        _ (validate-start-policy! {:allowed-penholders allowed-penholders
                                   :allow-empty-penholders? allow-empty-penholders?
                                   :compat-penholder compat-penholder})
        node (xtnode/start-node! (xtdb-config {:data-dir data-dir}))
        store (xtnode/xtdb-store node)
        handler (-> (http-app/ring-handler {:node node
                                            :store store
                                            :data-dir data-dir
                                            :allowed-penholders allowed-penholders
                                            :allowed-expansions (or allowed-expansions #{})
                                            :allowed-tooling (or allowed-tooling #{})
                                            :compat/penholder compat-penholder})
                    (http-app/wrap-static static-dir))
        server (jetty/run-jetty handler {:port port :join? false})
        actual-port (jetty-port server)
        sys (cond-> {:http/server server
             :http/port actual-port
             :http/handler handler
             :config {:data-dir data-dir
                      :port port
                      :allowed-penholders allowed-penholders
                      :allowed-expansions (or allowed-expansions #{})
                      :allowed-tooling (or allowed-tooling #{})
                      :allow-empty-penholders? allow-empty-penholders?
                      :expose-internals? expose-internals?}}
              expose-internals? (assoc :node node :store store))]
    (assoc sys
           :stop! (fn []
                    (try
                      (.stop ^org.eclipse.jetty.server.Server server)
                      (finally
                        (xtnode/stop-node! node)))
                    (assoc sys :stopped? true)))))

(defn- parse-allowed-penholders
  [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove empty?)
       (set)))

(defn -main
  "Run the Prototype 1 system as a long-lived process.

   Env vars:
   - FUTON1A_DATA_DIR (required)
   - FUTON1A_PORT (optional, default 7071)
   - FUTON1A_ALLOWED_PENHOLDERS (required by default, comma-separated)
   - FUTON1A_ALLOW_EMPTY_PENHOLDERS (optional, default false)
   - FUTON1A_STATIC_DIR (optional, serves static files from this directory)"
  [& _args]
  (let [data-dir (System/getenv "FUTON1A_DATA_DIR")
        port (some-> (System/getenv "FUTON1A_PORT") parse-long)
        static-dir (some-> (System/getenv "FUTON1A_STATIC_DIR") str/trim not-empty)
        allowed (parse-allowed-penholders (System/getenv "FUTON1A_ALLOWED_PENHOLDERS"))
        allow-empty? (parse-bool (System/getenv "FUTON1A_ALLOW_EMPTY_PENHOLDERS") false)
        compat-ph (some-> (System/getenv "FUTON1A_COMPAT_PENHOLDER") str/trim not-empty)
        sys (start! {:data-dir data-dir
                     :port (or port 7071)
                     :static-dir static-dir
                     :allowed-penholders allowed
                     :allow-empty-penholders? allow-empty?
                     :allowed-expansions #{}
                     :allowed-tooling #{}
                     :compat/penholder compat-ph})]
    (println "futon1a up"
             (cond-> {:http {:port (:http/port sys)}
                      :xtdb {:data-dir (get-in sys [:config :data-dir])}
                      :allow-empty-penholders? (get-in sys [:config :allow-empty-penholders?])
                      :allowed-penholders (vec (sort (get-in sys [:config :allowed-penholders])))}
               static-dir (assoc :static-dir static-dir)))
    (println "try:"
             (str "curl -s -H 'accept: application/edn' http://127.0.0.1:" (:http/port sys) "/health"))
    (flush)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn [] ((:stop! sys)))))
    ;; Park forever; shutdown hook will stop node/server.
    (Thread/sleep Long/MAX_VALUE)))
