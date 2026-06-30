(ns futon1a.layer4.mission-id-gate-test
  "E-futon1a-archivist slice 1: the L4 write-gate enforces the canonical
   :mission/doc id-contract (owner: claude-2). Canonical accepted, island/drift
   rejected as layer-4 400, known aliases queued (not rejected) onto the
   migration worklist — and every non-canonical decision is recorded (visible,
   not a silent drop)."
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
  "A minimally-valid :mission/doc entity with the given id."
  [id]
  {:entity/id id
   :entity/type :mission/doc
   :entity/name "x"
   :entity/external-id "x"})

(defn- ingest [& ids]
  (ow/ingest! {:entities (mapv mission ids) :relations []}))

(deftest canonical-mission-ids-accepted
  (testing "canonical <repo>-d/mission/<id>, incl. mixed-case + phase suffixes"
    (let [resp (ingest "futon3c-d/mission/M-first-flights"
                       "futon7-d/mission/IRC-stability"          ; mixed-case canonical
                       "futon3c-d/mission/M-typed-bells-ARGUE")] ; phase suffix
      (is (= 3 (get-in resp [:counts :models-validated])))
      (is (= 0 (get-in resp [:counts :models-queued])))
      (is (empty? (gate-queue/snapshot))
          "canonical writes leave no gate record"))))

(deftest non-canonical-mission-ids-rejected
  (testing "islands, allow-list misses, and dot-drift all bounce"
    (doseq [id ["mission:M-autoclock-in"                        ; O3 island
                "mission/M-typed-holes"                         ; mine island
                "futon3c-desktop-save-d/mission/x"              ; backup-checkout drift
                "futon5-d2/mission/x"                           ; not a real repo
                "futon3c-d/mission/substrate-metric.OR-sample"]] ; out-of-charset dot
      (is (thrown? clojure.lang.ExceptionInfo (ingest id))
          (str id " must be rejected"))))
  (testing "each rejection is recorded — visible, not silent"
    (is (= 5 (count (gate-queue/snapshot))))
    (is (every? #(= :rejected (:gate/disposition %)) (gate-queue/snapshot)))))

(deftest rejection-is-layer4-400-non-canonical
  (let [data (try (ingest "mission:M-foo")
                  (catch clojure.lang.ExceptionInfo ex (ex-data ex)))]
    (is (= 4 (get-in data [:error :error/layer])))
    (is (= 400 (get-in data [:error :error/status])))
    (is (= :non-canonical-id (get-in data [:error :error/reason])))
    (is (= :mission/doc (get-in data [:error :error/context :entity/type])))))

(deftest aliases-queued-not-rejected
  (testing "bare M-* (live capability-ingest writer) and mission|* are queued"
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
