(ns futon1a.scripts.seed-futon1-descriptors
  "Seed futon1a with futon1-style model descriptors as the first dataset (Prototype 2).

   This writes:
   - :model/descriptor entities (one per scope)
   - :model/entity-type entities (one per entity type per descriptor)
   - :model/invariant entities (one per invariant keyword)
   - :model/has-entity-type and :model/has-invariant relations

   The seed is intentionally small but uses the full descriptor shape (2.11.1)."
  (:require [clojure.string :as str]
            [futon1a.core.pipeline :as pipeline]
            [futon1a.model.registry :as registry]
            [xtdb.api :as xtdb]))

;; --- Canonical mission identity contract (E-futon1a-archivist) ----------------
;; Owner: claude-2 (data model). Grounded in the 708 canonical :mission/doc nodes:
;; <repo> is an ALLOW-LIST of the 13 real repos (a wildcard regex would wave
;; through drift labels like futon3c-desktop-save-d); <id> = [A-Za-z0-9-]+
;; (mixed-case is canonical; a '.' is out-of-charset drift).

(def mission-doc-repo-allow-list
  ["futon0-d" "futon2-d" "futon3-d" "futon3a-d" "futon3b-d" "futon3c-d"
   "futon4-d" "futon4-elisp-d" "futon5-d" "futon5a-d" "futon6-d" "futon6-py-d"
   "futon7-d"])

(def mission-doc-id-pattern
  (str "^(" (str/join "|" mission-doc-repo-allow-list)
       ")/mission/[A-Za-z0-9-]+$"))

;; Known aliases → QUEUE (migration worklist), not reject: bare M-* is a live
;; writer (capability-ingest) and the mine's mission|M-* — canonicalizing both
;; needs a repo the gate can't infer, so an owner resolves them later.
(def mission-doc-queue-patterns
  ["^M-[^/]+$" "^mission[|]"])

(def mission-doc-descriptor
  "The :mission/doc model the L4 write-gate reads. :id-pattern / :queue /
   :repo-allow-list are the new id-contract fields the gate honours."
  {:required [:entity/name :entity/external-id]
   :id-strategy :name
   :id-pattern mission-doc-id-pattern
   :queue mission-doc-queue-patterns
   :repo-allow-list mission-doc-repo-allow-list})

(defn register-mission-contract!
  "Load the :mission/doc canonical-id contract into the in-memory model registry
   so the L4 write-gate enforces it. Idempotent. Call once after (Drawbridge)
   reload to activate the gate on a running server."
  []
  (registry/register-model! {:id :mission/doc
                             :descriptor mission-doc-descriptor
                             :override? true}))

(defn- descriptor
  [{:keys [scope version penholder issued-at entities invariants]}]
  {:model/scope scope
   :schema/version version
   :schema/certificate {:penholder penholder
                        :issued-at issued-at}
   :entities entities
   :operations {}
   :stores {:canonical :xtdb}
   :invariants invariants})

(def futon1-descriptors
  "Seed descriptors that futon1a must be able to represent and ingest.

   Note: this is not a full reimplementation of futon1's verify logic; it is a
   faithful container for descriptor shape and cross-reference structure."
  [(descriptor {:scope :patterns
                :version "0.1.0"
                :penholder "futon1"
                :issued-at 0
                :entities {:pattern/language {:required [:entity/name :entity/external-id]
                                              :id-strategy :custom}
                           :pattern/library {:required [:entity/name :entity/external-id]
                                             :id-strategy :custom}
                           :pattern/component {:required [:entity/name :entity/external-id]
                                               :id-strategy :custom}
                           :sigil/sigil {:required [:entity/name :entity/external-id]
                                         :id-strategy :custom}}
                :invariants [:patterns/language-has-source]})
   (descriptor {:scope :media
                :version "0.1.0"
                :penholder "futon1"
                :issued-at 0
                :entities {:arxana/media-track {:required [:entity/name :entity/external-id]
                                                :id-strategy :custom}
                           :arxana/media-lyrics {:required [:entity/name :entity/external-id :entity/source :media/sha256]
                                                 :id-strategy :custom}}
                :invariants [:media/track-required]})
   (descriptor {:scope :docbook
                :version "0.1.0"
                :penholder "futon1"
                :issued-at 0
                :entities {:docbook/heading {:required [:doc/id :doc/book]
                                             :id-strategy :custom}
                           :docbook/entry {:required [:doc/entry-id :doc/book]
                                           :id-strategy :custom}
                           :docbook/toc {:required [:doc/id :doc/book]
                                         :id-strategy :custom}}
                :invariants [:docbook/heading-required]})
   (descriptor {:scope :open-world-ingest
                :version "0.1.0"
                :penholder "futon1"
                :issued-at 0
                :entities {:open-world/entity {:required [:entity/id :entity/type]
                                               :id-strategy :custom}
                           :open-world/relation {:required [:relation/id :relation/from :relation/to]
                                                 :id-strategy :custom}}
                :invariants [:open-world/entity-required]})
   (descriptor {:scope :meta-model
                :version "0.1.0"
                :penholder "futon1"
                :issued-at 0
                :entities {:model/descriptor {:required [:model/scope :schema/version :schema/certificate :entities]
                                              :id-strategy :custom}}
                :invariants [:meta/descriptor-has-certificate]})
   (descriptor {:scope :penholder-registry
                :version "0.1.0"
                :penholder "futon1"
                :issued-at 0
                :entities {:model/penholder {:required [:penholder/id :penholder/allowed?]
                                             :id-strategy :custom}}
                :invariants [:penholder/entry-required]})
   (descriptor {:scope :mission
                :version "0.1.0"
                :penholder "claude-2"
                :issued-at 0
                :entities {:mission/doc mission-doc-descriptor}
                :invariants [:mission/canonical-id]})])

(defn- kw->id [k] (str k))

(defn- descriptor-eid [scope] (str "model|descriptor|" (kw->id scope)))
(defn- entity-type-eid [scope type-id] (str "model|entity-type|" (kw->id scope) "|" (kw->id type-id)))
(defn- invariant-eid [inv] (str "model|invariant|" (kw->id inv)))
(defn- rel-id [from type to] (str "rel|" from "|" (kw->id type) "|" to))

(defn build-seed
  "Return {:entities [...] :relations [...] :tx-ops [...]}"
  []
  (let [entities (transient [])
        relations (transient [])
        tx-ops (transient [])]
    (doseq [d futon1-descriptors]
      (let [scope (:model/scope d)
            deid (descriptor-eid scope)
            ddoc (assoc d
                        :xt/id deid
                        :entity/id deid
                        :entity/type :model/descriptor)]
        (conj! entities {:entity/id deid :entity/type :model/descriptor})
        (conj! tx-ops [::xtdb/put ddoc])
        (doseq [[type-id defn] (:entities d)]
          (let [eid (entity-type-eid scope type-id)
                edoc (cond-> {:xt/id eid
                              :entity/id eid
                              :entity/type :model/entity-type
                              :model/scope scope
                              :type/id type-id
                              :required (:required defn)
                              :id-strategy (:id-strategy defn)}
                       (:id-pattern defn) (assoc :id-pattern (:id-pattern defn))
                       (:queue defn) (assoc :queue (:queue defn))
                       (:repo-allow-list defn) (assoc :repo-allow-list (:repo-allow-list defn)))
                rid (rel-id deid :model/has-entity-type eid)
                rdoc {:xt/id rid
                      :relation/id rid
                      :relation/type :model/has-entity-type
                      :relation/from deid
                      :relation/to eid}]
            (conj! entities {:entity/id eid :entity/type :model/entity-type})
            (conj! relations {:relation/id rid :relation/from deid :relation/to eid :relation/type :model/has-entity-type})
            (conj! tx-ops [::xtdb/put edoc])
            (conj! tx-ops [::xtdb/put rdoc])))
        (doseq [inv (:invariants d)]
          (let [ieid (invariant-eid inv)
                idoc {:xt/id ieid
                      :entity/id ieid
                      :entity/type :model/invariant
                      :invariant/id inv}
                rid (rel-id deid :model/has-invariant ieid)
                rdoc {:xt/id rid
                      :relation/id rid
                      :relation/type :model/has-invariant
                      :relation/from deid
                      :relation/to ieid}]
            (conj! entities {:entity/id ieid :entity/type :model/invariant})
            (conj! relations {:relation/id rid :relation/from deid :relation/to ieid :relation/type :model/has-invariant})
            (conj! tx-ops [::xtdb/put idoc])
            (conj! tx-ops [::xtdb/put rdoc])))))
    {:entities (persistent! entities)
     :relations (persistent! relations)
     :tx-ops (persistent! tx-ops)}))

(defn ingest!
  "Ingest futon1 descriptors as graph data through the open-world pipeline.

   Expects the same inputs as pipeline/run-open-world! for auth + durability."
  [{:keys [store penholder allowed-penholders tooling allowed-tooling]}]
  (let [{:keys [entities relations tx-ops]} (build-seed)]
    (pipeline/run-open-world! {:store store
                               :penholder penholder
                               :allowed-penholders allowed-penholders
                               :tooling tooling
                               :allowed-tooling allowed-tooling
                               :entities entities
                               :relations relations
                               :require-model? false
                               :tx-ops tx-ops
                               :claim {:op :seed/futon1-descriptors}
                               :detail {:layer :seed}})))

