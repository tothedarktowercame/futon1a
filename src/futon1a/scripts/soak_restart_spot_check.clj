(ns futon1a.scripts.soak-restart-spot-check
  "Operational restart-cycle spot check for futon1a.

   Lifecycle:
   start -> write -> read -> stop -> restart -> read -> stop"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [futon1a.system :as sys])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Instant)))

(defn- now-iso []
  (.toString (Instant/now)))

(defn- usage []
  (str/join
   "\n"
   ["Usage:"
    "  clojure -M -m futon1a.scripts.soak-restart-spot-check --data-dir DIR [--penholder NAME] [--out PATH]"
    ""
    "Defaults:"
    "  --penholder soak-audit"]))

(defn- parse-args [args]
  (loop [args args opts {}]
    (if (empty? args)
      opts
      (let [[a b & rest] args]
        (cond
          (= a "--data-dir") (recur rest (assoc opts :data-dir b))
          (= a "--penholder") (recur rest (assoc opts :penholder b))
          (= a "--out") (recur rest (assoc opts :out b))
          (= a "--help") (assoc opts :help? true)
          :else (throw (ex-info "unknown arg" {:arg a :args args})))))))

(defn- http-client []
  (HttpClient/newHttpClient))

(defn- add-headers
  [builder headers]
  (reduce-kv (fn [b k v]
               (.header b
                        (if (keyword? k) (name k) (str k))
                        (str v)))
             builder
             headers))

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
     :body (binding [*read-eval* false]
             (edn/read-string raw))}))

(defn run-check!
  [{:keys [data-dir penholder]}]
  (when-not (and (string? data-dir) (seq (str/trim data-dir)))
    (throw (ex-info "data-dir required" {:data-dir data-dir})))
  (let [penholder (or (some-> penholder str/trim not-empty) "soak-audit")
        client (http-client)
        entity-id (str "restart-spot-" (System/currentTimeMillis))
        boot (fn []
               (sys/start! {:data-dir data-dir
                            :port 0
                            :allowed-penholders #{penholder}}))]
    (loop [phase :boot-1
           sys1 nil
           report {:ok? false
                   :at (now-iso)
                   :data-dir data-dir
                   :penholder penholder
                   :entity/id entity-id}]
      (case phase
        :boot-1
        (let [s (boot)
              base (str "http://127.0.0.1:" (:http/port s))
              write-resp (request-edn client :post (str base "/write")
                                      :body {:penholder penholder
                                             :model {}
                                             :identity nil
                                             :tx-ops [[:xtdb.api/put {:xt/id entity-id
                                                                      :entity/id entity-id
                                                                      :entity/type :ops/restart
                                                                      :entity/name entity-id}]]})
              read-before (request-edn client :get (str base "/entity/" entity-id))]
          (recur :boot-2
                 s
                 (assoc report
                        :write write-resp
                        :read-before-restart read-before)))

        :boot-2
        (do
          ((:stop! sys1))
          (let [s (boot)
                base (str "http://127.0.0.1:" (:http/port s))
                read-after (request-edn client :get (str base "/entity/" entity-id))
                ok? (and (= 200 (get-in report [:write :status]))
                         (= 200 (get-in report [:read-before-restart :status]))
                         (= 200 (:status read-after))
                         (string? (get-in report [:write :body :path/id])))
                final (assoc report
                             :read-after-restart read-after
                             :ok? ok?)]
            ((:stop! s))
            final))))))

(defn -main
  [& args]
  (try
    (let [{:keys [help? out] :as opts} (parse-args args)]
      (if help?
        (do
          (println (usage))
          (System/exit 0))
        (let [report (run-check! opts)]
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
