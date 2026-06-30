(ns futon1a.ingest.open-world
  "Open-world ingest with validation.

   Tension:  Open-world velocity vs validation (T4).
   Pattern:  storage/open-world-velocity-validation"
  (:require [futon1a.core.entity :as ent]
            [futon1a.model.gate-queue :as gate-queue]
            [futon1a.model.registry :as registry]
            [futon1a.model.validation :as mv]))

(defn- classify-id
  "Classify an :entity/id against a descriptor's canonical-id contract
   (E-futon1a-archivist). The contract (supplied by the model owner, claude-2):

   - matches :id-pattern        => :accept (canonical)
   - else matches any :queue re => :queue  (a known alias; goes to the migration
                                            worklist, doesn't break the live writer)
   - else                       => :reject (island / drift / out-of-charset)

   A descriptor with no :id-pattern is unconstrained => :accept (back-compat)."
  [entity-id {:keys [id-pattern queue]}]
  (cond
    (nil? id-pattern) :accept
    (and entity-id (re-find (re-pattern id-pattern) entity-id)) :accept
    (and entity-id (some #(re-find (re-pattern %) entity-id) queue)) :queue
    :else :reject))

(defn- validate-model
  [{:keys [entity require-model?]}]
  (let [model-id (:entity/type entity)
        descriptor (registry/get-model model-id)]
    (cond
      descriptor
      ;; The canonical identifier may live in a field other than :entity/id
      ;; (missions carry it in :entity/name; the watcher's :xt/id is a UUID until
      ;; the :id==:name root-fix). The descriptor names that field via :id-field.
      (let [id-field (get descriptor :id-field :entity/id)
            id-val (get entity id-field)]
        (case (classify-id id-val descriptor)
          :reject
          (do
            (gate-queue/record! {:entity-id id-val
                                 :entity-type model-id
                                 :disposition :rejected
                                 :reason :non-canonical-id
                                 :expected (:id-pattern descriptor)})
            (throw (ex-info "non-canonical entity id"
                            {:error (mv/layer4-error
                                     :non-canonical-id
                                     {:entity/id id-val
                                      :id-field id-field
                                      :entity/type model-id
                                      :expected (:id-pattern descriptor)})})))

          ;; A known alias: record it on the migration worklist but let it through
          ;; so the live writer isn't broken. L0-diversion is the follow-on.
          :queue
          (do
            (gate-queue/record! {:entity-id id-val
                                 :entity-type model-id
                                 :disposition :queued
                                 :reason :alias-pending-migration
                                 :expected (:id-pattern descriptor)})
            (merge (mv/validate-model! {:model entity
                                        :required-keys (:required descriptor)})
                   {:queued? true}))

          :accept
          (mv/validate-model! {:model entity
                               :required-keys (:required descriptor)})))

      require-model?
      (throw (ex-info "model descriptor missing"
                      {:error (mv/layer4-error :missing-model
                                               {:model model-id
                                                :entity entity})}))

      :else
      {:ok? true :skipped? true})))

(defn ingest!
  "Ingest open-world data.

   Inputs:
   - entities (vector)
   - relations (vector)
   - require-model? (boolean, optional) => enforce registry presence

   Returns {:ok? true :counts {...}} or throws.
  "
  [{:keys [entities relations require-model?]}]
  (doseq [e entities]
    (ent/validate-entity e))
  (let [model-results (mapv (fn [entity]
                              (validate-model {:entity entity
                                               :require-model? require-model?}))
                            entities)
        model-validated (count (remove :skipped? model-results))
        model-missing (count (filter :skipped? model-results))
        model-queued (count (filter :queued? model-results))]
    (when (seq relations)
      (ent/validate-relations {:entities entities :relations relations}))
    {:ok? true
     :counts {:entities (count entities)
              :relations (count relations)
              :models-validated model-validated
              :models-missing model-missing
              :models-queued model-queued}}))
