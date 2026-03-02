(ns futon1a.scripts.soak-daily-audit
  "Daily operational soak audit for a running futon1a instance.

   Checks:
   - health endpoints
   - error-shape contract (L4 + L3 + L2 counter-ratchet)
   - proof-path emission on write
   - optional proof-log persistence check"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [futon1a.diag.proof-path :as proof])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Instant)))

(defn- now-iso []
  (.toString (Instant/now)))

(defn- usage []
  (str/join
   "\n"
   ["Usage:"
    "  clojure -M -m futon1a.scripts.soak-daily-audit \\"
    "    [--base-url URL] [--penholder NAME] [--proof-log-path PATH] [--strict-proof-log true|false] [--out PATH]"
    ""
    "Defaults:"
    "  --base-url http://127.0.0.1:7071"
    "  --penholder soak-audit"
    "  --strict-proof-log false"
    ""
    "Notes:"
    "  - Target instance must already be running."
    "  - Proof-log persistence is optional unless --strict-proof-log=true."]))

(defn- parse-bool [s default]
  (if (nil? s)
    default
    (let [v (-> s str str/trim str/lower-case)]
      (not (contains? #{"0" "false" "no" "off"} v)))))

(defn- parse-args [args]
  (loop [args args opts {}]
    (if (empty? args)
      opts
      (let [[a b & rest] args]
        (cond
          (= a "--base-url") (recur rest (assoc opts :base-url b))
          (= a "--penholder") (recur rest (assoc opts :penholder b))
          (= a "--proof-log-path") (recur rest (assoc opts :proof-log-path b))
          (= a "--strict-proof-log") (recur rest (assoc opts :strict-proof-log (parse-bool b false)))
          (= a "--out") (recur rest (assoc opts :out b))
          (= a "--help") (assoc opts :help? true)
          :else (throw (ex-info "unknown arg" {:arg a :args args})))))))

(defn- http-client []
  (HttpClient/newHttpClient))

(defn- add-headers
  [builder headers]
  (reduce-kv (fn [b k v]
               (.header b (if (keyword? k) (name k) (str k)) (str v)))
             builder
             headers))

(defn- parse-edn-body [s]
  (when (and (string? s) (seq (str/trim s)))
    (binding [*read-eval* false]
      (edn/read-string s))))

(defn- request-edn
  [^HttpClient client method url & {:keys [body headers]}]
  (let [headers (merge {"accept" "application/edn"} (or headers {}))
        body-str (when (some? body) (pr-str body))
        headers (if body-str
                  (assoc headers "content-type" "application/edn")
                  headers)
        builder (HttpRequest/newBuilder (URI/create url))
        builder (add-headers builder headers)
        builder (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString (or body-str "")))
                  (.method builder (str/upper-case (name method)) (HttpRequest$BodyPublishers/noBody)))
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))
        raw (.body resp)]
    {:status (.statusCode resp)
     :raw raw
     :body (try
             (parse-edn-body raw)
             (catch Exception _
               {:parse/error true :raw raw}))}))

(defn- read-proof-log [path]
  (let [f (io/file path)]
    (when (.isFile f)
      (->> (slurp f)
           (str/split-lines)
           (remove str/blank?)
           (mapv (fn [line]
                   (binding [*read-eval* false]
                     (edn/read-string line))))))))

(defn- ensure-error-shape
  [resp expected-status expected-layer]
  (let [layer (get-in resp [:body :error :layer])]
    {:ok? (and (= expected-status (:status resp))
               (= expected-layer layer))
     :status (:status resp)
     :error (get resp :body)}))

(defn run-audit!
  [{:keys [base-url penholder proof-log-path strict-proof-log]}]
  (let [base-url (str/replace (or base-url "http://127.0.0.1:7071") #"/+$" "")
        penholder (or (some-> penholder str/trim not-empty) "soak-audit")
        strict-proof-log (boolean strict-proof-log)
        client (http-client)
        pid (str "soak-audit-" (System/currentTimeMillis))
        put-doc {:xt/id pid :entity/id pid :entity/type :ops/soak :entity/name pid}
        healthz (request-edn client :get (str base-url "/healthz"))
        health (request-edn client :get (str base-url "/health"))
        check-healthz {:ok? (= 200 (:status healthz))
                       :status (:status healthz)}
        check-health {:ok? (and (= 200 (:status health))
                                (= :ok (get-in health [:body :status])))
                      :status (:status health)
                      :service-status (get-in health [:body :status])}
        l4 (request-edn client :post (str base-url "/write")
                        :body {:penholder penholder
                               :model {}
                               :identity nil
                               :tx-ops {}})
        check-l4 (ensure-error-shape l4 400 4)
        l3 (request-edn client :post (str base-url "/write")
                        :body {:model {}
                               :identity nil
                               :tx-ops [[:xtdb.api/put put-doc]]})
        check-l3 (ensure-error-shape l3 403 3)
        write-ok (request-edn client :post (str base-url "/write")
                              :body {:penholder penholder
                                     :model {}
                                     :identity nil
                                     :tx-ops [[:xtdb.api/put put-doc]]})
        delete-blocked (request-edn client :post (str base-url "/write")
                                    :body {:penholder penholder
                                           :model {}
                                           :identity nil
                                           :tx-ops [[:xtdb.api/delete pid]]})
        check-l2 (ensure-error-shape delete-blocked 500 2)
        path-id (get-in write-ok [:body :path/id])
        check-proof-path {:ok? (and (= 200 (:status write-ok)) (string? path-id))
                          :status (:status write-ok)
                          :path/id path-id}
        proof-log-entries (when proof-log-path
                            (try
                              (read-proof-log proof-log-path)
                              (catch Exception _
                                nil)))
        matched-path (when (and (seq proof-log-entries) (string? path-id))
                       (first (filter #(= path-id (:path/id %)) proof-log-entries)))
        persisted-ok? (boolean (and matched-path
                                    (:ok? (proof/validate-complete-path matched-path))))
        check-proof-log {:ok? (if strict-proof-log persisted-ok? true)
                         :strict? strict-proof-log
                         :proof-log-path proof-log-path
                         :persisted? persisted-ok?}
        checks {:healthz check-healthz
                :health check-health
                :error-shape/l4 check-l4
                :error-shape/l3 check-l3
                :error-shape/l2-counter-ratchet check-l2
                :proof-path check-proof-path
                :proof-log check-proof-log}
        ok? (every? true? (map :ok? (vals checks)))]
    {:ok? ok?
     :at (now-iso)
     :target base-url
     :penholder penholder
     :checks checks}))

(defn -main
  [& args]
  (try
    (let [{:keys [help? out] :as opts} (parse-args args)]
      (if help?
        (do
          (println (usage))
          (System/exit 0))
        (let [report (run-audit! opts)]
          (println (pr-str report))
          (when out
            (spit out (str (pr-str report) "\n")))
          (System/exit (if (:ok? report) 0 1)))))
    (catch Exception e
      (println (pr-str {:ok? false
                        :at (now-iso)
                        :error (.getMessage e)
                        :data (ex-data e)}))
      (System/exit 2))))
