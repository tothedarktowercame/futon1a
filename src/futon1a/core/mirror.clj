(ns futon1a.core.mirror
  "Datascript ↔ XTDB mirroring (stub)."
  (:require [futon1a.core.invariants :as inv]))

(defprotocol MirrorStore
  (put-entity! [store entity] "Store entity in mirror.")
  (put-relation! [store relation] "Store relation in mirror."))

(defn mirror-entity!
  "Mirror a single entity into the target store." [store entity]
  (when-not (:entity/id entity)
    (throw (ex-info "entity missing id"
                    {:error (inv/layer2-error :missing-id {:entity entity})})))
  (put-entity! store entity))

(defn mirror-relation!
  "Mirror a single relation into the target store." [store relation]
  (when-not (:relation/id relation)
    (throw (ex-info "relation missing id"
                    {:error (inv/layer2-error :missing-id {:relation relation})})))
  (put-relation! store relation))

(defn mirror-batch!
  "Mirror a batch of entities and relations.

   Returns {:ok? true :counts {:entities n :relations m}} or throws." 
  [store {:keys [entities relations]}]
  (let [entity-ids (map :entity/id entities)
        relation-ids (map :relation/id relations)
        dup-entity-ids (->> entity-ids frequencies (filter (fn [[_ v]] (> v 1))) (map first))
        dup-relation-ids (->> relation-ids frequencies (filter (fn [[_ v]] (> v 1))) (map first))]
    (when (seq dup-entity-ids)
      (throw (ex-info "duplicate entity ids in mirror batch"
                      {:error (inv/layer2-error :duplicate-id {:ids dup-entity-ids})})))
    (when (seq dup-relation-ids)
      (throw (ex-info "duplicate relation ids in mirror batch"
                      {:error (inv/layer2-error :duplicate-id {:ids dup-relation-ids})})))
    (doseq [entity entities]
      (mirror-entity! store entity))
    (doseq [relation relations]
      (mirror-relation! store relation))
    {:ok? true
     :counts {:entities (count entities)
              :relations (count relations)}}))

(defrecord InMemoryMirrorStore [entities relations]
  MirrorStore
  (put-entity! [_ entity]
    (let [eid (:entity/id entity)
          existing (get @entities eid)]
      (when (and existing (not= existing entity))
        (throw (ex-info "mirror entity mismatch"
                        {:error (inv/layer2-error :mirror-mismatch
                                                  {:entity entity
                                                   :existing existing})})))
      (swap! entities assoc eid entity)
      entity))
  (put-relation! [_ relation]
    (let [rid (:relation/id relation)
          existing (get @relations rid)]
      (when (and existing (not= existing relation))
        (throw (ex-info "mirror relation mismatch"
                        {:error (inv/layer2-error :mirror-mismatch
                                                  {:relation relation
                                                   :existing existing})})))
      (swap! relations assoc rid relation)
      relation)))

(defn in-memory-store
  "Create an in-memory mirror store." []
  (->InMemoryMirrorStore (atom {}) (atom {})))

(defn mirror-snapshot
  "Return a snapshot of mirror contents when supported."
  [store]
  (when (instance? InMemoryMirrorStore store)
    {:entities @(:entities store)
     :relations @(:relations store)}))

(defrecord StubMirrorStore []
  MirrorStore
  (put-entity! [_ entity] entity)
  (put-relation! [_ relation] relation))
