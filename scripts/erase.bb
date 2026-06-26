#!/usr/bin/env bb
;; erase.bb — the gated GDPR right-to-erasure CLI (E-clean-up-substrate-2 part B).
;;
;; There is NO API surface for erasure (it is destructive: XTDB evict removes a
;; doc AND its whole bitemporal history). This CLI drives the RUNNING futon1a
;; node via Drawbridge (:6768, admin-token'd) as penholder "joe" — the only
;; penholder `pipeline/run-erase!` accepts. It runs in-process through the gated
;; pipeline (L4 validate → L3 authz → L0 evict + audit), never a raw evict.
;;
;; DEFAULT IS DRY-RUN. You must pass --execute to actually evict, and --execute
;; requires --reason (recorded in the compliance audit). For a type-doc the
;; dry-run prints the type's POPULATION so you never erase the catalogue entry
;; of a type that still has data.
;;
;;   bb scripts/erase.bb --type :arxana/media-lyrics              # dry-run
;;   bb scripts/erase.bb --eids "type|entity|:foo,type|relation|:foo"
;;   bb scripts/erase.bb --type :arxana/media-lyrics --reason "noise dedup" --execute
;;   bb scripts/erase.bb --eids "id1,id2" --reason "GDPR erasure req #N" --execute
(require '[babashka.http-client :as http]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def TOKEN (str/trim (slurp "/home/joe/code/futon3c/.admintoken")))
(def DRAWBRIDGE "http://127.0.0.1:6768/eval")
(def PENHOLDER "joe")

(defn eval! [form]
  (let [resp (http/post DRAWBRIDGE
                        {:headers {"x-admin-token" TOKEN "Content-Type" "text/plain"}
                         :body form :throw false})]
    (when (not= 200 (:status resp))
      (binding [*out* *err*] (println "Drawbridge eval failed:" (:status resp) (:body resp)))
      (System/exit 1))
    (let [out (edn/read-string (:body resp))]
      (if (:ok out) (:value out)
          (do (binding [*out* *err*] (println "eval error:" (pr-str out))) (System/exit 2))))))

(defn parse-args [args]
  (loop [m {:execute? false} [a & more] args]
    (cond
      (nil? a) m
      (= a "--execute") (recur (assoc m :execute? true) more)
      (= a "--reason")  (recur (assoc m :reason (first more)) (rest more))
      (= a "--type")    (recur (assoc m :type (first more)) (rest more))
      (= a "--eids")    (recur (assoc m :eids (vec (remove str/blank? (str/split (first more) #",")))) (rest more))
      :else (recur m more))))

;; Resolve targets to concrete xt/ids (for --type, all present kind/encoding variants).
(defn resolve-eids [{:keys [type eids]}]
  (cond
    (seq eids) eids
    type (eval! (str "(futon1a.model.type-registry/type-xt-ids-present"
                     " (:node @futon3c.dev/!f1-sys) " type ")"))
    :else []))

;; Dry-run: per eid — existence, the type's population, and how many OTHER
;; type-docs share this type-id (siblings). The guard warns only when erasing
;; would leave a POPULATED type with NO surviving doc; a redundant encoding
;; whose canonical sibling survives is flagged safe (the data keys on the
;; type-id keyword, not on this doc's xt/id).
(defn dry-run [eids]
  (eval! (str "(let [db (xtdb.api/db (:node @futon3c.dev/!f1-sys))"
              "      cnt (fn [attr tid] (or (ffirst (xtdb.api/q db {:find ['(count e)] :where [['e attr tid]]})) 0))]"
              "  (mapv (fn [eid]"
              "          (let [doc (xtdb.api/entity db eid) tid (:type/id doc) kind (:type/kind doc)"
              "                pop (when tid (if (= kind :relation) (cnt :hx/type tid) (cnt :entity/type tid)))"
              "                sibs (if tid (count (remove #(= % eid) (map first (xtdb.api/q db {:find ['e] :where [['e :type/id tid]]})))) 0)]"
              "            {:eid eid :exists? (some? doc) :type-id (str tid) :kind (str kind) :population pop :siblings sibs}))"
              "        " (pr-str eids) "))")))

(defn execute! [eids reason]
  (eval! (str "(futon1a.core.pipeline/run-erase!"
              " {:store (:store @futon3c.dev/!f1-sys) :penholder " (pr-str PENHOLDER)
              " :eids " (pr-str eids) " :reason " (pr-str reason) "})")))

(defn -main [& args]
  (let [{:keys [execute? reason] :as opts} (parse-args args)
        eids (resolve-eids opts)]
    (when (empty? eids)
      (println "No targets. Use --eids \"a,b\" or --type :some/type.") (System/exit 0))
    (println (format "Targets (%d):" (count eids)))
    (doseq [{:keys [eid exists? type-id kind population siblings]} (dry-run eids)]
      (let [pop? (and population (pos? population))
            orphan? (and pop? (zero? (or siblings 0)))
            tag (cond
                  orphan? "  ⚠️ ORPHANS DATA — populated type, no surviving doc"
                  (and pop? (pos? (or siblings 0))) (format "  ✓ safe: populated, but %d canonical sibling(s) survive" siblings)
                  :else "  ✓ safe: empty")]
        (println (format "  %-44s exists=%s%s" eid exists?
                         (if (and type-id (not= type-id ""))
                           (format "  type=%s kind=%s population=%s%s" type-id kind population tag)
                           "")))))
    (if-not execute?
      (println "\nDRY-RUN (default). Re-run with --reason \"...\" --execute to evict.")
      (do
        (when (str/blank? (str reason))
          (binding [*out* *err*] (println "\n--execute requires --reason (GDPR audit).")) (System/exit 3))
        (println (format "\nEXECUTING erase as penholder \"%s\" — reason: %s" PENHOLDER reason))
        (let [r (execute! (vec eids) reason)]
          (println "RESULT:" (pr-str r)))))))

(apply -main *command-line-args*)
