;; textprobe-history-sample.clj — M-text-sidecar P1 (history leg).
;;
;; Extracts the bitemporal version history of a pre-selected id sample so the
;; ever-held vs current token divergence (XTDB #5637's stress metric) can be
;; measured offline. See futon2/holes/M-text-sidecar.md.
;;
;; RUN CONSTRAINTS — read before loading:
;;   * QUIET WINDOW ONLY. ~8k entity-history point reads against the live
;;     store; cheap individually, but don't stack them on migration exports.
;;   * Load as a Drawbridge side-file (futon3c/scripts/proof-eval.sh -f).
;;     It kicks off a `future` and returns immediately — no heavy synchronous
;;     Drawbridge call. Poll `@futon1a.scripts.textprobe-status` or watch the
;;     output file grow.
;;   * Reads only; touches no shared namespaces, reloads nothing.
;;
;; Input:  /home/joe/code/futon1b/textprobe/history-sample-ids.edn
;;         (produced offline by futon1b/textprobe_sample.clj)
;; Output: /home/joe/code/futon1b/textprobe/history-versions.edn
;;         one {:id :band :n :versions [...]} per sampled doc; each version
;;         carries only vt/tt and the text-bearing fields (census P1a), so the
;;         output stays small and the tokenization happens offline.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[xtdb.api :as xtdb])

(defonce textprobe-status (atom {:state :idle}))
(intern (create-ns 'futon1a.scripts) 'textprobe-status textprobe-status)

(let [ids-file "/home/joe/code/futon1b/textprobe/history-sample-ids.edn"
      out-file "/home/joe/code/futon1b/textprobe/history-versions.edn"
      ;; text-bearing field paths per the P1a census (graph + evidence)
      text-paths [[:entity/source]
                  [:entity/name]
                  [:entity/props :anchor/passage]
                  [:entity/props :anchor/heading]
                  [:evidence/body :text]]
      node (:node @(requiring-resolve 'futon3c.dev/!f1-sys))]
  (if-not node
    (reset! textprobe-status
            {:state :error
             :reason :node-not-exposed
             :hint "futon1a started without :expose-internals? — fall back to the snapshot-endpoint route"})
    (do
      (reset! textprobe-status {:state :running :done 0})
      (future
        (try
          (let [{:keys [bands]} (edn/read-string (slurp ids-file))
                extract (fn [doc]
                          (into {}
                                (keep (fn [p]
                                        (when-let [v (get-in doc p)]
                                          (when (string? v) [p v]))))
                                text-paths))
                work (for [[band ids] bands, id ids] [band id])
                total (count work)]
            (io/make-parents out-file)
            (with-open [w (io/writer out-file)]
              (.write w "[\n")
              (doseq [[i [band id]] (map-indexed vector work)]
                (let [hist (xtdb/entity-history (xtdb/db node) id :asc {:with-docs? true})
                      versions (mapv (fn [h]
                                       {:vt (:xtdb.api/valid-time h)
                                        :tt (:xtdb.api/tx-time h)
                                        :deleted? (nil? (:xtdb.api/doc h))
                                        :fields (extract (:xtdb.api/doc h))})
                                     hist)]
                  (.write w (pr-str {:id id :band band :n (count versions) :versions versions}))
                  (.write w "\n"))
                (when (zero? (mod (inc i) 250))
                  (swap! textprobe-status assoc :done (inc i) :total total)
                  ;; breathe between batches — this is a live store
                  (Thread/sleep 200)))
              (.write w "]\n"))
            (reset! textprobe-status {:state :done :total total :out out-file}))
          (catch Throwable t
            (reset! textprobe-status {:state :error :ex (str t)}))))
      {:kicked-off true :status-var 'futon1a.scripts/textprobe-status :out out-file})))
