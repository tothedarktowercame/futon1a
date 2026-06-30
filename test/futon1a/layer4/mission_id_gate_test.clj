(ns futon1a.layer4.mission-id-gate-test
  "E-futon1a-archivist: the L4 write-gate enforces the canonical :mission/doc
   id-contract (owner: claude-2, full-population verified). The canonical
   identifier is the :entity/name (the watcher's :xt/id is a UUID until the
   :id==:name root-fix), so the gate checks :id-field = :entity/name. Mixed-case
   accepted (18 real nodes are), M- prefix rejected (twin guard), islands/drift
   rejected as layer-4 400, known aliases queued — every non-canonical decision
   recorded (visible, not a silent drop)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [futon1a.ingest.open-world :as ow]
            [futon1a.model.gate-queue :as gate-queue]
            [futon1a.model.registry :as registry]
            [futon1a.scripts.seed-futon1-descriptors :as seed]))

(use-fixtures :each (fn [f]
                      (registry/clear-registry!)
                      (gate-queue/clear!)
                      (seed/register-mission-contract!)
                      (f)))

(defn- mission
  "A minimally-valid :mission/doc entity whose canonical identifier (:entity/name,
   the gated field) is `nm`."
  [nm]
  {:entity/id nm
   :entity/type :mission/doc
   :entity/name nm
   :entity/external-id "M-x"})

(defn- ingest [& names]
  (ow/ingest! {:entities (mapv mission names) :relations []}))

(deftest canonical-mission-names-accepted
  (testing "canonical <repo>-d/mission/<name>, lowercase AND mixed-case"
    (let [resp (ingest "futon3c-d/mission/first-flights"
                       "futon7-d/mission/IRC-stability"            ; mixed-case (the 18)
                       "futon0-d/mission/P3-rational-reconstruction")]
      (is (= 3 (get-in resp [:counts :models-validated])))
      (is (= 0 (get-in resp [:counts :models-queued])))
      (is (empty? (gate-queue/snapshot))
          "canonical writes leave no gate record"))))

(deftest non-canonical-mission-names-rejected
  (testing "islands, allow-list misses, dot-drift, and M- twins all bounce"
    (doseq [nm ["mission:M-autoclock-in"                         ; O3 island
                "mission/M-typed-holes"                          ; mine island
                "futon3c-desktop-save-d/mission/x"               ; backup-checkout drift
                "futon5-d2/mission/x"                            ; not a real repo
                "futon3c-d/mission/substrate-metric.OR-sample"   ; out-of-charset dot
                "futon3c-d/mission/M-First-Flights"]]            ; M- prefix twin
      (is (thrown? clojure.lang.ExceptionInfo (ingest nm))
          (str nm " must be rejected"))))
  (testing "each rejection is recorded — visible, not silent"
    (is (= 6 (count (gate-queue/snapshot))))
    (is (every? #(= :rejected (:gate/disposition %)) (gate-queue/snapshot)))))

(deftest rejection-is-layer4-400-non-canonical
  (let [data (try (ingest "mission:M-foo")
                  (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
    (is (= 4 (get-in data [:error :error/layer])))
    (is (= 400 (get-in data [:error :error/status])))
    (is (= :non-canonical-id (get-in data [:error :error/reason])))
    (is (= :entity/name (get-in data [:error :error/context :id-field])))
    (is (= :mission/doc (get-in data [:error :error/context :entity/type])))))

(deftest aliases-queued-not-rejected
  (testing "bare M-* and mission|* names are queued onto the worklist, not rejected"
    (let [resp (ingest "M-capability-star-map" "mission|M-old")]
      ;; let through (not rejected) so the live writer isn't broken ...
      (is (= 2 (get-in resp [:counts :models-validated])))
      ;; ... but recorded on the migration worklist
      (is (= 2 (get-in resp [:counts :models-queued])))
      (is (= 2 (count (gate-queue/snapshot))))
      (is (every? #(= :queued (:gate/disposition %)) (gate-queue/snapshot)))
      (is (every? #(= :alias-pending-migration (:gate/reason %))
                  (gate-queue/snapshot))))))

(deftest unconstrained-types-unaffected
  (testing "a type whose descriptor has no :id-pattern is not id-gated"
    (registry/register-model! {:id :plain
                               :descriptor {:fields [:entity/id :entity/type]
                                            :required [:entity/type]}})
    (let [resp (ow/ingest! {:entities [{:entity/id "anything-goes"
                                        :entity/type :plain}]
                            :relations []})]
      (is (= 1 (get-in resp [:counts :models-validated])))
      (is (empty? (gate-queue/snapshot))))))

;; The /entity compat path (run-write!) the watcher/capability-ingest/agents use
;; goes through ow/gate-entity-id!, NOT ow/ingest!. These lock that path so the
;; gate is not inert on the live write path (E-futon1a-archivist cutover).
(deftest gate-entity-id!-rejects-islands-on-write-path
  (testing "non-canonical :entity/name throws (L4 400) and is recorded"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ow/gate-entity-id! (mission "mission:M-autoclock-in"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ow/gate-entity-id! (mission "futon3c-desktop-save-d/mission/x"))))
    (is (= 2 (count (gate-queue/snapshot))))
    (is (every? #(= :rejected (:gate/disposition %)) (gate-queue/snapshot)))))

(deftest gate-entity-id!-accepts-canonical-and-queues-aliases
  (testing "canonical => nil (accept); bare M-* => {:queued? true} + recorded; no descriptor => nil"
    (is (nil? (ow/gate-entity-id! (mission "futon3c-d/mission/autoclock-in"))))
    (is (nil? (ow/gate-entity-id! (mission "futon7-d/mission/IRC-stability"))))
    (is (empty? (gate-queue/snapshot)) "accepts leave no record")
    (is (= {:queued? true} (ow/gate-entity-id! (mission "M-5"))))
    (is (= 1 (count (gate-queue/snapshot))))
    (is (nil? (ow/gate-entity-id! {:entity/type :no-descriptor-type :entity/name "x"}))
        "type with no descriptor is a no-op")))
