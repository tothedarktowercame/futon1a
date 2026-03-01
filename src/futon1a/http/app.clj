(ns futon1a.http.app
  "Ring adapter for futon1a routes.

   Prototype 1 requirement: serve the API surface over HTTP.

   Content types:
   - Requests: EDN (`application/edn`) bodies are supported for POST endpoints.
   - Responses: EDN bodies are returned as `application/edn`.

   Compatibility:
   - Supports a minimal Futon1-style JSON surface used by futon3:
     `POST /entity`, `POST /relation`, and `POST/GET /api/alpha/lab/session...`"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [futon1a.api.routes :as routes]
            [ring.middleware.content-type :as ct-mw]
            [ring.middleware.file :as file-mw]
            [ring.util.codec :as codec]
            [ring.util.response :as resp]
            [xtdb.api :as xtdb])
  (:import [java.io File]))

(defn- url-decode
  "Decode a URL-encoded path segment."
  [s]
  (when (string? s)
    (try
      (java.net.URLDecoder/decode s "UTF-8")
      (catch Exception _
        s))))

(defn- edn-response
  ([m] (edn-response 200 m))
  ([status m]
   (-> (resp/response (pr-str m))
       (resp/status status)
       (resp/content-type "application/edn; charset=utf-8"))))

(defn- json-response
  ([m] (json-response 200 m))
  ([status m]
   (-> (resp/response (json/encode m {:escape-non-ascii true}))
       (resp/status status)
       (resp/content-type "application/json; charset=utf-8"))))

(defn- wants-json?
  [req]
  (let [accept (some-> (get-in req [:headers "accept"]) str/lower-case)
        ctype (some-> (get-in req [:headers "content-type"]) str/lower-case)]
    (or (and accept (str/includes? accept "application/json"))
        (and ctype (str/includes? ctype "application/json")))))

(defn- response
  [req status body]
  (if (wants-json? req)
    (json-response status body)
    (edn-response status body)))

(defn- parse-edn-body
  "Parse request body as EDN map. Empty bodies yield {}.

   Note: this intentionally keeps parsing simple and explicit for Prototype 1."
  [{:keys [body]}]
  (let [s (some-> body slurp str/trim)]
    (cond
      (nil? s) {}
      (empty? s) {}
      :else
      (let [v (edn/read-string {:readers *data-readers*} s)]
        (if (map? v) v {:_body v})))))

(defn- parse-json-body
  [{:keys [body]}]
  (let [s (some-> body slurp str/trim)]
    (cond
      (nil? s) {}
      (empty? s) {}
      :else
      (let [v (json/decode s true)]
        (if (map? v) v {:_body v})))))

(defn- parse-body
  [req]
  (let [ctype (some-> (get-in req [:headers "content-type"]) str/lower-case)]
    (cond
      (and ctype (str/includes? ctype "application/json")) (parse-json-body req)
      :else (parse-edn-body req))))

(defn- not-found
  [req]
  (response req 404 {:error {:reason :not-found
                             :path (:uri req)
                             :method (:request-method req)}}))

(defn- method-not-allowed
  [req allowed]
  (response req 405 {:error {:reason :method-not-allowed
                             :path (:uri req)
                             :method (:request-method req)
                             :allowed allowed}}))

(defn- entity-id-from-uri
  "Extract entity id from `/entity/<id>`."
  [uri]
  (when (and (string? uri) (str/starts-with? uri "/entity/"))
    (subs uri (count "/entity/"))))

(defn- alpha-strip
  "If `uri` is under /api/alpha, return the stripped path (starting with /).
   Otherwise returns nil."
  [uri]
  (let [prefix "/api/alpha"]
    (when (and (string? uri) (str/starts-with? uri prefix))
      (let [rest (subs uri (count prefix))]
        (if (seq rest) rest "/")))))

(defn- api-strip
  "If `uri` is under /api (but not /api/alpha), return the stripped path
  (starting with /). Otherwise returns nil.

  Arxana's `arxana-store` normalizes its base URL to end in /api, then uses
  paths like /entity/<id> and /relation; that yields requests like
  /api/entity/<id> which must be supported for futon1a replacement mode."
  [uri]
  (let [prefix "/api"]
    (when (and (string? uri)
               (str/starts-with? uri prefix)
               (not (str/starts-with? uri "/api/alpha")))
      (let [rest (subs uri (count prefix))]
        (if (seq rest) rest "/")))))

(defn- ego-name-from-uri
  "Extract name from `/ego/<name>`."
  [uri]
  (when (and (string? uri) (str/starts-with? uri "/ego/"))
    (subs uri (count "/ego/"))))

(defn- alpha-lab-session-id-from-uri
  "Extract session id from `/api/alpha/lab/session/<id>`."
  [uri]
  (let [prefix "/api/alpha/lab/session/"]
    (when (and (string? uri) (str/starts-with? uri prefix))
      (subs uri (count prefix)))))

(defn- normalize-type
  [t]
  (cond
    (keyword? t) t
    (string? t) (let [s (-> t str/trim)]
                  (when (seq s)
                    (keyword (if (str/starts-with? s ":") (subs s 1) s))))
    :else nil))

(defn- require-field
  [req m k]
  (when-not (contains? m k)
    (throw (ex-info "missing required field" {:status 400 :missing k :path (:uri req)})))
  m)

(defn- compat-penholder
  "Derive a penholder for Futon1-compat endpoints.
   Priority: request body -> x-penholder header -> system default."
  [system req body]
  (or (:penholder body)
      (some-> (get-in req [:headers "x-penholder"]) str/trim not-empty)
      (:compat/penholder system)))

(defn- futon1-compat-write!
  "Write doc into XTDB via futon1a pipeline (keeps L3 auth + L0 durability)."
  [{:keys [store allowed-penholders] :as system} req penholder doc-id doc]
  (let [resp (routes/write {:store store
                            :penholder penholder
                            :allowed-penholders (or allowed-penholders #{})
                            :model {}
                            :identity nil
                            :tx-ops [[::xtdb/put (assoc doc :xt/id doc-id)]]
                            :claim {:op :compat/write}
                            :detail {:layer :http
                                     :path (:uri req)}})]
    resp))

(defn ring-handler
  "Return a Ring handler using a running XTDB node and policy config.

   system keys:
   - node (XTDB node)
   - store (DurableStore; typically from futon1a.core.xtdb-node/xtdb-store)
   - data-dir (string; used for snapshot export/restore artifacts)
   - allowed-penholders (set of strings)
   - allowed-expansions (set, optional)
   - allowed-tooling (set, optional)
   - compat/penholder (string, optional) default penholder for Futon1-compat writes"
  [{:keys [node store data-dir allowed-penholders allowed-expansions allowed-tooling] :as system}]
  (fn [req]
    (let [{:keys [request-method uri]} req
          body-map (when (#{:post :put :patch} request-method) (parse-body req))
          query-params (when (= request-method :get)
                         (codec/form-decode (or (:query-string req) "")))
          alpha-uri (alpha-strip uri)
          api-uri (api-strip uri)
          base-req (merge body-map
                          {:store store
                           :allowed-penholders (or allowed-penholders #{})
                           :allowed-expansions (or allowed-expansions #{})
                           :allowed-tooling (or allowed-tooling #{})})]
      (cond
        (and (= request-method :get) (= uri "/health"))
        (let [resp (routes/health {:checks {:xtdb (fn []
                                                   (try
                                                     (let [st (xtdb/status node)]
                                                       {:ok? true :status st})
                                                     (catch Exception e
                                                       {:ok? false :error (.getMessage e)})))}})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (= uri "/healthz"))
        (let [resp (routes/health {:checks {:xtdb (fn []
                                                   (try
                                                     (do (xtdb/status node) {:ok? true})
                                                     (catch Exception e
                                                       {:ok? false :error (.getMessage e)})))}})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/write"))
        (let [resp (routes/write base-req)]
          (response req (:status resp) (:body resp)))

        ;; Futon3 bridge endpoints (minimal JSON surface)
        (and (= request-method :post) (= uri "/entity"))
        (try
          (let [body body-map
                _ (require-field req body :name)
                _ (require-field req body :type)
                doc-id (or (:external-id body) (:name body))
                penholder (compat-penholder system req body)
                doc {:futon1a/compat :entity
                     :name (:name body)
                     :type (normalize-type (:type body))
                     :external-id (:external-id body)
                     :notes (:notes body)
                     :payload (:payload body)} 
                resp (futon1-compat-write! system req penholder doc-id doc)]
            (response req (:status resp) (:body resp)))
          (catch clojure.lang.ExceptionInfo e
            (response req (or (:status (ex-data e)) 400) {:error (.getMessage e) :data (ex-data e)})))

        (and (= request-method :post) (= uri "/relation"))
        (try
          (let [body body-map
                _ (require-field req body :type)
                _ (require-field req body :src)
                _ (require-field req body :dst)
                doc-id (or (:external-id body)
                           (str (:src body) "|" (:type body) "|" (:dst body)))
                penholder (compat-penholder system req body)
                doc {:futon1a/compat :relation
                     :type (normalize-type (:type body))
                     :src (:src body)
                     :dst (:dst body)
                     :notes (:notes body)
                     :payload (:payload body)}
                resp (futon1-compat-write! system req penholder doc-id doc)]
            (response req (:status resp) (:body resp)))
          (catch clojure.lang.ExceptionInfo e
            (response req (or (:status (ex-data e)) 400) {:error (.getMessage e) :data (ex-data e)})))

        ;; Futon1 API parity writes (used by futon3 scripts like scripts/pattern_sync.clj)
        (and (= request-method :post) (= uri "/api/alpha/entity"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-ensure-entity {:node node
                                                 :store store
                                                 :penholder penholder
                                                 :allowed-penholders (or allowed-penholders #{})
                                                 :profile (get-in req [:headers "x-profile"])
                                                 :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; /api alias for Arxana store bridge (arxana-store normalizes base to /api).
        (and (= request-method :post) api-uri (= api-uri "/entity"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-ensure-entity {:node node
                                                 :store store
                                                 :penholder penholder
                                                 :allowed-penholders (or allowed-penholders #{})
                                                 :profile (get-in req [:headers "x-profile"])
                                                 :payload payload})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/api/alpha/relation"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-upsert-relation {:node node
                                                   :store store
                                                   :penholder penholder
                                                   :allowed-penholders (or allowed-penholders #{})
                                                   :profile (get-in req [:headers "x-profile"])
                                                   :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; /api alias for Arxana store bridge.
        (and (= request-method :post) api-uri (= api-uri "/relation"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-upsert-relation {:node node
                                                   :store store
                                                   :penholder penholder
                                                   :allowed-penholders (or allowed-penholders #{})
                                                   :profile (get-in req [:headers "x-profile"])
                                                   :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; Futon4 arxana-store batch relation surface (org link sync).
        (and (= request-method :post) alpha-uri (= alpha-uri "/relations/batch"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-upsert-relations-batch {:node node
                                                         :store store
                                                         :penholder penholder
                                                         :allowed-penholders (or allowed-penholders #{})
                                                         :profile (get-in req [:headers "x-profile"])
                                                         :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; Futon4 arxana-store hyperedge surface.
        (and (= request-method :post) alpha-uri (= alpha-uri "/hyperedge"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-upsert-hyperedge {:node node
                                                    :store store
                                                    :penholder penholder
                                                    :allowed-penholders (or allowed-penholders #{})
                                                    :profile (get-in req [:headers "x-profile"])
                                                    :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; /api alias for hyperedge.
        (and (= request-method :post) api-uri (= api-uri "/hyperedge"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-upsert-hyperedge {:node node
                                                    :store store
                                                    :penholder penholder
                                                    :allowed-penholders (or allowed-penholders #{})
                                                    :profile (get-in req [:headers "x-profile"])
                                                    :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; /api alias for batch relations.
        (and (= request-method :post) api-uri (= api-uri "/relations/batch"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-upsert-relations-batch {:node node
                                                         :store store
                                                         :penholder penholder
                                                         :allowed-penholders (or allowed-penholders #{})
                                                         :profile (get-in req [:headers "x-profile"])
                                                         :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; Hyperedge read by ID
        (and (= request-method :get)
             (str/starts-with? uri "/api/alpha/hyperedge/"))
        (let [hx-id (url-decode (subs uri (count "/api/alpha/hyperedge/")))
              resp (routes/hyperedge-by-id {:node node :id hx-id})]
          (response req (:status resp) (:body resp)))

        ;; Hyperedge query (by type or by endpoint)
        (and (= request-method :get) (= uri "/api/alpha/hyperedges"))
        (let [qtype (get query-params "type")
              qend (get query-params "end")
              qlimit (when-let [s (get query-params "limit")]
                       (try (Integer/parseInt s) (catch Exception _ nil)))
              resp (cond
                     qtype (routes/hyperedges-by-type {:node node :hx-type qtype :limit qlimit})
                     qend (routes/hyperedges-by-end {:node node :end-id qend :limit qlimit})
                     :else {:status 400
                            :body {:error "type or end parameter required"}})]
          (response req (:status resp) (:body resp)))

        ;; Futon4 snapshot surface (filesystem export + pipeline restore).
        (and (= request-method :post) alpha-uri (= alpha-uri "/snapshot"))
        (let [payload body-map
              resp (routes/snapshot-save {:node node
                                          :data-dir data-dir
                                          :scope (:scope payload)
                                          :label (:label payload)})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) api-uri (= api-uri "/snapshot"))
        (let [payload body-map
              resp (routes/snapshot-save {:node node
                                          :data-dir data-dir
                                          :scope (:scope payload)
                                          :label (:label payload)})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) alpha-uri (= alpha-uri "/snapshot/restore"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/snapshot-restore {:store store
                                             :penholder penholder
                                             :allowed-penholders (or allowed-penholders #{})
                                             :allowed-expansions (or allowed-expansions #{})
                                             :allowed-tooling (or allowed-tooling #{})
                                             :data-dir data-dir
                                             :scope (:scope payload)
                                             :payload payload})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) api-uri (= api-uri "/snapshot/restore"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/snapshot-restore {:store store
                                             :penholder penholder
                                             :allowed-penholders (or allowed-penholders #{})
                                             :allowed-expansions (or allowed-expansions #{})
                                             :allowed-tooling (or allowed-tooling #{})
                                             :data-dir data-dir
                                             :scope (:scope payload)
                                             :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; Arxana media surface (expects /api prefix, not /api/alpha)
        (and (= request-method :post) (= uri "/api/media/lyrics"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/upsert-media-lyrics {:store store
                                                :penholder penholder
                                                :allowed-penholders (or allowed-penholders #{})
                                                :profile (get-in req [:headers "x-profile"])
                                                :payload payload})]
          (response req (:status resp) (:body resp)))

        ;; Accept the same surface under /api/alpha for parity/testing.
        (and (= request-method :post) alpha-uri (= alpha-uri "/media/lyrics"))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/upsert-media-lyrics {:store store
                                                :penholder penholder
                                                :allowed-penholders (or allowed-penholders #{})
                                                :profile (get-in req [:headers "x-profile"])
                                                :payload payload})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/api/alpha/lab/session"))
        (try
          (let [body body-map
                session-id (or (:lab/session-id body) (:session/id body))
                _ (when-not (and (string? session-id) (seq (str/trim session-id)))
                    (throw (ex-info "session id required" {:status 400 :expected #{:lab/session-id :session/id}})))
                penholder (compat-penholder system req body)
                doc (assoc body
                           :futon1a/compat :lab/session
                           :lab/session-id session-id)
                resp (futon1-compat-write! system req penholder session-id doc)]
            (response req (:status resp) (:body resp)))
          (catch clojure.lang.ExceptionInfo e
            (response req (or (:status (ex-data e)) 400) {:error (.getMessage e) :data (ex-data e)})))

        (and (= request-method :get) (str/starts-with? uri "/api/alpha/lab/session/"))
        (let [sid (alpha-lab-session-id-from-uri uri)
              db (xtdb/db node)
              doc (when (seq sid) (xtdb/entity db sid))]
          (if doc
            (response req 200 doc)
            (response req 404 {:error "not found" :lab/session-id sid})))

        ;; Futon1 API parity endpoints (read surface used by futon3)
        (and (= request-method :get) alpha-uri (= alpha-uri "/entity"))
        (let [src (get query-params "source")
              ext (get query-params "external-id")
              resp (routes/entity-by-external {:node node :source src :external-id ext})]
          (response req (:status resp) (:body resp)))

        ;; /api alias for external-id lookup.
        (and (= request-method :get) api-uri (= api-uri "/entity"))
        (let [src (get query-params "source")
              ext (get query-params "external-id")
              resp (routes/entity-by-external {:node node :source src :external-id ext})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (str/starts-with? alpha-uri "/entity/"))
        (let [eid (some-> (entity-id-from-uri alpha-uri) url-decode)
              resp (routes/compat-entity {:node node
                                         :profile (get-in req [:headers "x-profile"])
                                         :id eid})]
          (response req (:status resp) (:body resp)))

        ;; /api alias for entity reads.
        (and (= request-method :get) api-uri (str/starts-with? api-uri "/entity/"))
        (let [eid (some-> (entity-id-from-uri api-uri) url-decode)
              resp (routes/compat-entity {:node node
                                         :profile (get-in req [:headers "x-profile"])
                                         :id eid})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (= alpha-uri "/entities/latest"))
        (let [type (get query-params "type")
              limit (some-> (get query-params "limit") (Long/parseLong))
              resp (routes/compat-entities-latest {:node node
                                                  :profile (get-in req [:headers "x-profile"])
                                                  :type type
                                                  :limit limit})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (str/starts-with? alpha-uri "/ego/"))
        (let [name (some-> (ego-name-from-uri alpha-uri) url-decode)
              resp (routes/compat-ego {:node node
                                      :profile (get-in req [:headers "x-profile"])
                                      :name name})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (= alpha-uri "/meta/model"))
        (let [resp (routes/compat-meta-model {:node node
                                              :profile (get-in req [:headers "x-profile"])
                                              :scope :patterns})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (= alpha-uri "/meta/model/verify"))
        (let [resp (routes/compat-meta-model-verify {:node node
                                                     :profile (get-in req [:headers "x-profile"])
                                                     :scope :patterns})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (= alpha-uri "/meta/model/docbook/verify"))
        (let [resp (routes/compat-meta-model-verify {:node node
                                                     :profile (get-in req [:headers "x-profile"])
                                                     :scope :docbook})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (= alpha-uri "/patterns/registry"))
        (let [resp (routes/compat-patterns-registry {:node node
                                                     :profile (get-in req [:headers "x-profile"])})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) alpha-uri (= alpha-uri "/types"))
        (let [resp (routes/list-types {:node node})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) alpha-uri (= alpha-uri "/types/parent"))
        (let [resp (routes/types-parent (merge base-req {:node node}))]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) alpha-uri (= alpha-uri "/types/merge"))
        (let [resp (routes/types-merge (merge base-req {:node node}))]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/models"))
        (let [resp (routes/register-model base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (= uri "/models"))
        (let [resp (routes/list-models)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (= uri "/meta/model"))
        (let [resp (routes/meta-models {:node node})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (str/starts-with? uri "/meta/model/"))
        (let [scope (subs uri (count "/meta/model/"))
              ;; Handle /meta/model/<scope>/verify.
              verify? (str/ends-with? scope "/verify")
              scope (if verify?
                      (subs scope 0 (- (count scope) (count "/verify")))
                      scope)
              scope (if (str/starts-with? scope ":") scope (str ":" scope))
              resp (if verify?
                     (routes/meta-model-verify {:node node :scope scope})
                     (routes/meta-model {:node node :scope scope}))]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (= uri "/types"))
        (let [resp (routes/list-types {:node node})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/types/parent"))
        (let [resp (routes/types-parent (merge base-req {:node node}))]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/types/merge"))
        (let [resp (routes/types-merge (merge base-req {:node node}))]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/__repair/legacy-descriptors"))
        ;; Explicit operational repair: upgrade migrated futon1 :model/descriptor docs
        ;; to satisfy Prototype 2 descriptor invariants.
        (let [dry-run? (boolean (:dry-run? body-map))
              ph (compat-penholder system req body-map)]
          (try
            (require 'futon1a.scripts.repair-legacy-descriptors)
            (let [repair! (resolve 'futon1a.scripts.repair-legacy-descriptors/repair!)
                  result (repair! {:node node
                                   :store store
                                   :penholder ph
                                   :allowed-penholders (or allowed-penholders #{})
                                   :dry-run? dry-run?})]
              (response req 200 result))
            (catch Exception e
              (response req 500 {:error {:reason :exception
                                         :message (.getMessage e)}}))))

        (and (= request-method :post) (= uri "/__repair/import-futon1-events-lyrics"))
        ;; Explicit operational migration: import lyrics from Futon1 durable event log.
        (let [dry-run? (boolean (:dry-run? body-map))
              ph (compat-penholder system req body-map)
              events-path (or (:events-path body-map) (:path body-map))]
          (try
            (require 'futon1a.scripts.import-futon1-events-lyrics)
            (let [import! (resolve 'futon1a.scripts.import-futon1-events-lyrics/import!)
                  result (import! {:store store
                                   :penholder ph
                                   :allowed-penholders (or allowed-penholders #{})
                                   :dry-run? dry-run?
                                   :events-path events-path})]
              (response req 200 result))
            (catch clojure.lang.ExceptionInfo e
              (response req (or (:status (ex-data e)) 400) {:error (.getMessage e) :data (ex-data e)}))
            (catch Exception e
              (response req 500 {:error {:reason :exception
                                         :message (.getMessage e)}}))))

        (and (= request-method :post) (= uri "/__repair/import-lyrics-recovery"))
        ;; Explicit operational migration: import lyrics from a recovery JSON + audio files.
        (let [dry-run? (boolean (:dry-run? body-map))
              ph (compat-penholder system req body-map)
              json-path (or (:json-path body-map) (:path body-map))
              audio-dir (:audio-dir body-map)
              slice-bytes (:slice-bytes body-map)]
          (try
            (require 'futon1a.scripts.import-lyrics-recovery)
            (let [import! (resolve 'futon1a.scripts.import-lyrics-recovery/import!)
                  result (import! {:store store
                                   :penholder ph
                                   :allowed-penholders (or allowed-penholders #{})
                                   :dry-run? dry-run?
                                   :json-path json-path
                                   :audio-dir audio-dir
                                   :slice-bytes slice-bytes})]
              (response req 200 result))
            (catch clojure.lang.ExceptionInfo e
              (response req (or (:status (ex-data e)) 400) {:error (.getMessage e) :data (ex-data e)}))
            (catch Exception e
              (response req 500 {:error {:reason :exception
                                         :message (.getMessage e)}}))))

        (and (= request-method :post) (= uri "/ingest"))
        (let [resp (routes/ingest base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/repair"))
        (let [resp (routes/repair base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/repair/verify"))
        (let [resp (routes/verify-repair base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (= uri "/entity"))
        (let [src (get query-params "source")
              ext (get query-params "external-id")
              resp (routes/entity-by-external {:node node :source src :external-id ext})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (str/starts-with? uri "/entity/"))
        (let [eid (some-> (entity-id-from-uri uri) url-decode)
              resp (routes/entity-by-id {:node node :id eid})]
          (response req (:status resp) (:body resp)))

        ;; Evidence landscape endpoints

        ;; POST /api/alpha/evidence — write an evidence entry
        (and (= request-method :post) (= uri "/api/alpha/evidence"))
        (try
          (let [body body-map
                ;; Accept both namespaced and unqualified keys
                eid (or (:evidence/id body) (:id body) (str (java.util.UUID/randomUUID)))
                at (or (:evidence/at body) (:at body) (.toString (java.time.Instant/now)))
                etype (normalize-type (or (:evidence/type body) (:type body)))
                ctype (normalize-type (or (:evidence/claim-type body) (:claim-type body)))
                author (or (:evidence/author body) (:author body))
                ebody (or (:evidence/body body) (:body body))
                subject (or (:evidence/subject body) (:subject body))
                _ (when-not etype
                    (throw (ex-info "evidence/type required" {:status 400})))
                _ (when-not ctype
                    (throw (ex-info "evidence/claim-type required" {:status 400})))
                _ (when-not author
                    (throw (ex-info "evidence/author required" {:status 400})))
                ;; Build the entry
                entry (cond-> {:xt/id eid
                               :evidence/id eid
                               :evidence/at at
                               :evidence/type etype
                               :evidence/claim-type ctype
                               :evidence/author author
                               :evidence/body (or ebody {})
                               :evidence/tags (vec (or (:evidence/tags body) (:tags body) []))}
                        subject
                        (assoc :evidence/subject subject)
                        (or (:evidence/pattern-id body) (:pattern-id body))
                        (assoc :evidence/pattern-id (or (:evidence/pattern-id body) (:pattern-id body)))
                        (or (:evidence/session-id body) (:session-id body))
                        (assoc :evidence/session-id (or (:evidence/session-id body) (:session-id body)))
                        (or (:evidence/in-reply-to body) (:in-reply-to body))
                        (assoc :evidence/in-reply-to (or (:evidence/in-reply-to body) (:in-reply-to body)))
                        (or (:evidence/fork-of body) (:fork-of body))
                        (assoc :evidence/fork-of (or (:evidence/fork-of body) (:fork-of body)))
                        (some? (or (:evidence/conjecture? body) (:conjecture? body)))
                        (assoc :evidence/conjecture? (boolean (or (:evidence/conjecture? body) (:conjecture? body))))
                        (some? (or (:evidence/ephemeral? body) (:ephemeral? body)))
                        (assoc :evidence/ephemeral? (boolean (or (:evidence/ephemeral? body) (:ephemeral? body)))))
                ;; Check for duplicate
                db (xtdb/db node)]
            (if (xtdb/entity db eid)
              (response req 409 {:error "duplicate evidence id" :evidence/id eid})
              (do
                (xtdb/await-tx node (xtdb/submit-tx node [[::xtdb/put entry]]))
                (response req 201 {:ok true :evidence/id eid :entry (dissoc entry :xt/id)}))))
          (catch clojure.lang.ExceptionInfo e
            (response req (or (:status (ex-data e)) 400)
                      {:error (.getMessage e) :data (dissoc (ex-data e) :status)})))

        (and (= request-method :get) (= uri "/api/alpha/evidence"))
        (let [db (xtdb/db node)
              qtype (some-> (get query-params "type") normalize-type)
              qclaim (some-> (get query-params "claim-type") normalize-type)
              qsubject-type (some-> (get query-params "subject-type") normalize-type)
              qsubject-id (get query-params "subject-id")
              qsession (get query-params "session-id")
              qauthor (get query-params "author")
              qsince (get query-params "since")
              qlimit (when-let [s (get query-params "limit")]
                       (try (Integer/parseInt s) (catch Exception _ nil)))
              ;; Base query: all evidence entries
              base-where '[[e :evidence/id id]
                           [e :evidence/at at]]
              ;; Build additional where clauses from params
              extra-where (cond-> []
                            qtype (conj ['e :evidence/type qtype])
                            qclaim (conj ['e :evidence/claim-type qclaim])
                            qsession (conj ['e :evidence/session-id qsession])
                            qauthor (conj ['e :evidence/author qauthor]))
              where-clauses (vec (concat base-where extra-where))
              query {:find '[(pull e [*])]
                     :where where-clauses}
              results (->> (xtdb/q db query)
                           (map first)
                           (map #(dissoc % :xt/id))
                           ;; Post-filter for subject (nested map)
                           (filter (fn [e]
                                     (and (or (nil? qsubject-type)
                                              (= qsubject-type (get-in e [:evidence/subject :ref/type])))
                                          (or (nil? qsubject-id)
                                              (= qsubject-id (get-in e [:evidence/subject :ref/id]))))))
                           ;; Post-filter for since
                           (filter (fn [e]
                                     (or (nil? qsince)
                                         (>= (compare (str (:evidence/at e)) qsince) 0))))
                           ;; Sort newest first
                           (sort-by :evidence/at #(compare %2 %1))
                           ;; Apply limit
                           ((fn [xs] (if (and qlimit (pos? qlimit)) (take qlimit xs) xs)))
                           vec)]
          (response req 200 {:entries results :count (count results)}))

        (and (= request-method :get)
             (str/starts-with? uri "/api/alpha/evidence/")
             (str/ends-with? uri "/chain"))
        (let [eid (-> uri
                      (subs (count "/api/alpha/evidence/"))
                      (str/replace #"/chain$" ""))
              db (xtdb/db node)]
          (loop [id eid acc [] seen #{}]
            (if (or (nil? id) (str/blank? id) (contains? seen id))
              (response req 200 {:chain (vec (reverse acc))})
              (let [doc (xtdb/entity db id)]
                (if doc
                  (let [entry (dissoc doc :xt/id)]
                    (recur (:evidence/in-reply-to entry)
                           (conj acc entry)
                           (conj seen id)))
                  (response req 200 {:chain (vec (reverse acc))}))))))

        (and (= request-method :get)
             (str/starts-with? uri "/api/alpha/evidence/"))
        (let [eid (subs uri (count "/api/alpha/evidence/"))
              db (xtdb/db node)
              doc (when (seq eid) (xtdb/entity db eid))]
          (if (and doc (:evidence/id doc))
            (response req 200 (dissoc doc :xt/id))
            (response req 404 {:error "not found" :evidence/id eid})))

        (= request-method :get)
        (not-found req)

        :else
        (method-not-allowed req #{:get :post})))))

(defn- wrap-index-html
  "Rewrite directory-style URIs (trailing /) to serve index.html.
   Without this, wrap-content-type sees no file extension on the URI
   and defaults to application/octet-stream even when wrap-file correctly
   resolves index.html from the directory."
  [handler dir]
  (fn [req]
    (let [uri (:uri req)]
      (if (and (str/ends-with? uri "/")
               (.isFile (File. ^String dir ^String (str (subs uri 1) "index.html"))))
        (handler (assoc req :uri (str uri "index.html")))
        (handler req)))))

(defn wrap-static
  "Optionally wrap handler to serve static files from dir.
   Returns handler unchanged when dir is nil or not a directory."
  [handler dir]
  (if (and dir (.isDirectory (File. ^String dir)))
    (-> handler
        (file-mw/wrap-file dir {:allow-symlinks? true})
        (wrap-index-html dir)
        (ct-mw/wrap-content-type))
    handler))
