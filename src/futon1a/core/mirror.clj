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

(defrecord StubMirrorStore []
  MirrorStore
  (put-entity! [_ entity] entity)
  (put-relation! [_ relation] relation))
