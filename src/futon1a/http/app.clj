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

(defn- path-segments
  "Split a path-like string into URL-decoded, non-empty segments."
  [s]
  (->> (str/split (or s "") #"/")
       (remove str/blank?)
       (mapv url-decode)))

(defn- alpha-docbook-route
  "If alpha URI is under /docs/:book, return {:book :segments}; else nil."
  [alpha-uri]
  (when-let [[_ raw-book tail] (and alpha-uri
                                    (re-matches #"^/docs/([^/]+)(?:/(.*))?$" alpha-uri))]
    {:book (url-decode raw-book)
     :segments (path-segments tail)}))

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
  [{:keys [store allowed-penholders]} req penholder doc-id doc]
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
          query-params (codec/form-decode (or (:query-string req) ""))
          alpha-uri (alpha-strip uri)
          api-uri (api-strip uri)
          docbook-route (alpha-docbook-route alpha-uri)
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
                                                     (xtdb/status node)
                                                     {:ok? true}
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
              qrepo (get query-params "repo")
              qsource-file (get query-params "source-file")
              qlimit (when-let [s (get query-params "limit")]
                       (try (Integer/parseInt s) (catch Exception _ nil)))
              resp (cond
                     qtype (routes/hyperedges-by-type {:node node
                                                       :hx-type qtype
                                                       :limit qlimit
                                                       :repo qrepo
                                                       :source-file qsource-file})
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

        ;; Futon1/Futon4 docbook compatibility surface.
        (and (= request-method :get) docbook-route (= ["contents"] (:segments docbook-route)))
        (let [resp (routes/compat-docbook-contents {:node node
                                                    :book (:book docbook-route)})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) docbook-route (= ["toc"] (:segments docbook-route)))
        (let [resp (routes/compat-docbook-toc {:node node
                                               :book (:book docbook-route)})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) docbook-route (= ["contents" "order"] (:segments docbook-route)))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-docbook-update-order {:node node
                                                        :store store
                                                        :penholder penholder
                                                        :allowed-penholders (or allowed-penholders #{})
                                                        :book (:book docbook-route)
                                                        :payload payload})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) docbook-route (= ["entry"] (:segments docbook-route)))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-docbook-entry {:store store
                                                 :penholder penholder
                                                 :allowed-penholders (or allowed-penholders #{})
                                                 :book (:book docbook-route)
                                                 :payload payload})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) docbook-route (= ["entries"] (:segments docbook-route)))
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-docbook-entries {:store store
                                                   :penholder penholder
                                                   :allowed-penholders (or allowed-penholders #{})
                                                   :book (:book docbook-route)
                                                   :payload payload})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) docbook-route (= "heading" (first (:segments docbook-route)))
             (= 2 (count (:segments docbook-route))))
        (let [[_ doc-id] (:segments docbook-route)
              resp (routes/compat-docbook-heading {:node node
                                                   :book (:book docbook-route)
                                                   :doc-id doc-id})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) docbook-route (= ["recent"] (:segments docbook-route)))
        (let [resp (routes/compat-docbook-recent {:node node
                                                  :book (:book docbook-route)
                                                  :limit (get query-params "limit")})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :delete) docbook-route (= "doc" (first (:segments docbook-route)))
             (= 2 (count (:segments docbook-route))))
        (let [[_ doc-id] (:segments docbook-route)
              penholder (compat-penholder system req body-map)
              resp (routes/compat-docbook-delete-doc {:node node
                                                      :store store
                                                      :penholder penholder
                                                      :allowed-penholders (or allowed-penholders #{})
                                                      :book (:book docbook-route)
                                                      :doc-id doc-id})]
          (response req (:status resp) (:body resp)))

        (and (= request-method :delete) docbook-route (= "toc" (first (:segments docbook-route)))
             (= 2 (count (:segments docbook-route))))
        (let [[_ doc-id] (:segments docbook-route)
              penholder (compat-penholder system req body-map)
              resp (routes/compat-docbook-delete-toc {:node node
                                                      :store store
                                                      :penholder penholder
                                                      :allowed-penholders (or allowed-penholders #{})
                                                      :book (:book docbook-route)
                                                      :doc-id doc-id
                                                      :cascade? (get query-params "cascade")})]
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
              fold? (contains? #{"1" "true" "yes"}
                               (str/lower-case (or (get query-params "fold") "")))
              depth (some-> (get query-params "depth") Long/parseLong)
              resp (routes/compat-ego {:node node
                                      :profile (get-in req [:headers "x-profile"])
                                      :name name
                                      :fold? fold?
                                      :depth depth})]
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
        (let [payload body-map
              penholder (compat-penholder system req payload)
              resp (routes/compat-write-evidence {:node node
                                                  :store store
                                                  :penholder penholder
                                                  :allowed-penholders (or allowed-penholders #{})
                                                  :payload payload})]
          (response req (:status resp) (:body resp)))

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
              qtags (when-let [s (get query-params "tags")]
                      (->> (str/split s #",")
                           (map str/trim)
                           (remove str/blank?)
                           seq))
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
                           ;; Post-filter for tags (AND semantics; tag matches
                           ;; if a stored tag's name equals the requested
                           ;; tag string, tolerant of keyword/string/symbol).
                           (filter (fn [e]
                                     (or (nil? qtags)
                                         (let [stored (or (:evidence/tags e) [])
                                               names (set (map (fn [t]
                                                                 (cond
                                                                   (keyword? t) (name t)
                                                                   (symbol? t) (name t)
                                                                   :else (str t)))
                                                               stored))]
                                           (every? (fn [t] (contains? names t)) qtags)))))
                           ;; Sort newest first
                           (sort-by :evidence/at #(compare %2 %1))
                           ;; Apply limit
                           ((fn [xs] (if (and qlimit (pos? qlimit)) (take qlimit xs) xs)))
                           vec)]
          (response req 200 {:entries results :count (count results)}))

        ;; GET /api/alpha/evidence/sessions — aggregator over the evidence
        ;; landscape that returns per-session summary
        ;; ({:session-id :count :types :latest-at :authors :first-at}
        ;;  ...) directly from XTDB, so consumers (Arxana Browser
        ;; evidence-sessions view, Stack HUD widgets) don't have to fetch
        ;; the whole entry stream and group client-side.
        ;;
        ;; Replaces the prior pattern of GET /evidence?limit=N + group-by-
        ;; session in the Emacs view, which capped at N most-recent entries
        ;; and silently misrepresented older sessions' counts.
        ;;
        ;; Optional query params:
        ;;   since=<iso-instant>  — restrict to sessions whose latest
        ;;                          entry is at-or-after this timestamp.
        ;;   limit=<int>          — cap result count (default unbounded).
        ;;   author=<string>      — restrict to sessions where any entry
        ;;                          carries this author.
        (and (= request-method :get) (= uri "/api/alpha/evidence/sessions"))
        (let [db (xtdb/db node)
              qsince (get query-params "since")
              qauthor (get query-params "author")
              qlimit (when-let [s (get query-params "limit")]
                       (try (Integer/parseInt s) (catch Exception _ nil)))
              ;; Pull only the four small fields we need (id/session/at/type/author);
              ;; avoiding (pull e [*]) keeps the result vector compact even
              ;; for ~10k entries.
              base-where '[[e :evidence/id _]
                           [e :evidence/session-id sid]
                           [e :evidence/at at]]
              where-clauses (cond-> base-where
                              qauthor (conj ['e :evidence/author qauthor]))
              raw (xtdb/q db
                          {:find '[sid at (pull e [:evidence/type :evidence/author])]
                           :where where-clauses})
              ;; Reshape to per-entry maps so reduce is straightforward.
              entries (->> raw
                           (map (fn [[sid at pulled]]
                                  {:session-id sid
                                   :at (str at)
                                   :type (str (or (:evidence/type pulled) ""))
                                   :author (str (or (:evidence/author pulled) ""))}))
                           (filter (fn [e]
                                     (or (nil? qsince)
                                         (>= (compare (:at e) qsince) 0)))))
              ;; Group by session-id; build summary per group.
              ;; ISO-8601 timestamps are lexically orderable, so use
              ;; clojure.core/compare directly rather than min-key/max-key
              ;; (which require Number key fns).
              sessions (->> entries
                            (group-by :session-id)
                            (map (fn [[sid items]]
                                   (let [ats (map :at items)
                                         sorted-ats (sort ats)
                                         types (->> items
                                                    (map :type)
                                                    (remove str/blank?)
                                                    distinct
                                                    sort
                                                    vec)
                                         authors (->> items
                                                      (map :author)
                                                      (remove str/blank?)
                                                      distinct
                                                      sort
                                                      vec)]
                                     {:session-id sid
                                      :count (count items)
                                      :types types
                                      :authors authors
                                      :first-at (first sorted-ats)
                                      :latest-at (last sorted-ats)})))
                            (sort-by :latest-at #(compare %2 %1))
                            ((fn [xs] (if (and qlimit (pos? qlimit)) (take qlimit xs) xs)))
                            vec)]
          (response req 200 {:window-since qsince
                             :author-filter qauthor
                             :total-sessions (count sessions)
                             :total-entries (count entries)
                             :sessions sessions}))

        ;; GET /api/alpha/patterns/activation — projection over
        ;; context-retrieval evidence: aggregate by pattern id and
        ;; return per-pattern activations with original queries +
        ;; back-pointers to the underlying turn evidence.
        ;; Single source of truth for the Arxana Browser activation
        ;; view, Stack HUD widget, and any agent-side curl probe.
        (and (= request-method :get) (= uri "/api/alpha/patterns/activation"))
        (let [db (xtdb/db node)
              qsince (get query-params "since")
              qlimit (when-let [s (get query-params "limit")]
                       (try (Integer/parseInt s) (catch Exception _ nil)))
              raw (->> (xtdb/q db '{:find [(pull e [*])]
                                    :where [[e :evidence/id _]
                                            [e :evidence/at _]]})
                       (map first)
                       (filter (fn [e]
                                 (let [body (:evidence/body e)
                                       ev (or (get body :event)
                                              (get body "event"))]
                                   (= ev "context-retrieval"))))
                       (filter (fn [e]
                                 (or (nil? qsince)
                                     (>= (compare (str (:evidence/at e)) qsince) 0))))
                       vec)
              activations (mapcat
                            (fn [e]
                              (let [body (:evidence/body e)
                                    results (or (get body :results)
                                                (get body "results"))
                                    qtext (str (or (get body :query)
                                                   (get body "query")
                                                   ""))]
                                (for [r (or results [])]
                                  {:id (or (get r :id) (get r "id"))
                                   :title (or (get r :title) (get r "title"))
                                   :score (or (get r :score) (get r "score"))
                                   :at (str (:evidence/at e))
                                   :session-id (:evidence/session-id e)
                                   :evidence-id (:evidence/id e)
                                   :agent-id (:evidence/author e)
                                   :query (subs qtext 0 (min 240 (count qtext)))})))
                            raw)
              grouped (->> activations
                           (group-by :id)
                           (map (fn [[pid items]]
                                  (let [scores (keep :score items)]
                                    {:id pid
                                     :title (some :title items)
                                     :count (count items)
                                     :avg-score (when (seq scores)
                                                  (double (/ (reduce + 0.0 scores)
                                                             (count scores))))
                                     :last-fired (->> items (map :at) sort last)
                                     :activations (vec (sort-by :at #(compare %2 %1) items))})))
                           (sort-by :count #(compare %2 %1))
                           ((fn [xs] (if (and qlimit (pos? qlimit)) (take qlimit xs) xs)))
                           vec)]
          (response req 200 {:window-since qsince
                             :total-retrievals (count raw)
                             :pattern-count (count grouped)
                             :patterns grouped}))

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
        (method-not-allowed req #{:get :post :delete})))))

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
        (ct-mw/wrap-content-type)
        (wrap-index-html dir))
    handler))
