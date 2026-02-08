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
            [ring.util.response :as resp]
            [xtdb.api :as xtdb]))

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
   - allowed-penholders (set of strings)
   - allowed-expansions (set, optional)
   - allowed-tooling (set, optional)
   - compat/penholder (string, optional) default penholder for Futon1-compat writes"
  [{:keys [node store allowed-penholders allowed-expansions allowed-tooling] :as system}]
  (fn [req]
    (let [{:keys [request-method uri]} req
          body-map (when (#{:post :put :patch} request-method) (parse-body req))
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

        ;; Futon1-compat endpoints (JSON surface)
        (and (= request-method :post) (or (= uri "/entity")
                                          (= uri "/api/alpha/entity")))
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

        (and (= request-method :post) (or (= uri "/relation")
                                          (= uri "/api/alpha/relation")))
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

        (and (= request-method :post) (= uri "/ingest"))
        (let [resp (routes/ingest base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/repair"))
        (let [resp (routes/repair base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :post) (= uri "/repair/verify"))
        (let [resp (routes/verify-repair base-req)]
          (response req (:status resp) (:body resp)))

        (and (= request-method :get) (str/starts-with? uri "/entity/"))
        (let [eid (entity-id-from-uri uri)
              db (xtdb/db node)
              ent (when (seq eid) (xtdb/entity db eid))]
          (if ent
            (response req 200 {:entity ent})
            (response req 404 {:error {:reason :not-found :entity/id eid}})))

        (= request-method :get)
        (not-found req)

        :else
        (method-not-allowed req #{:get :post})))))
