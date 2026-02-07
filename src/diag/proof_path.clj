(ns futon1a.diag.proof-path
  "Proof-path event logging scaffold (Part II, 2.1).

   Pure data model for ordered proof-path events. Storage and IO are handled
   by callers; this module only validates and constructs event maps."
  (:import (java.util UUID)))

(def ^:private proof-path-phases
  [:clock-in
   :observe
   :propose-claim
   :apply-change
   :verify
   :invariant-check
   :proof-commit
   :clock-out])

(def ^:private phase->index
  (zipmap proof-path-phases (range)))

(defn- now-ms [] (System/currentTimeMillis))

(defn- uuid [] (str (UUID/randomUUID)))

(defn valid-phase? [phase]
  (contains? phase->index phase))

(defn next-phase? [prev next]
  (and (valid-phase? prev)
       (valid-phase? next)
       (= (inc (phase->index prev)) (phase->index next))))

(defn new-path
  "Create a new, empty proof-path log." []
  {:path/id (uuid)
   :path/created-at (now-ms)
   :events []})

(defn event
  "Build a proof-path event. Caller supplies :path/id and :actor.

   Required keys in attrs:
   - :path/id (string)
   - :actor   (string)
   - :phase   (keyword)

   Optional keys:
   - :claim   (any)
   - :detail  (any)
   - :tx-id   (string)
   - :error   (map)
   - :evidence (map)
  "
  [{:path/keys [id]
    :keys [actor phase claim detail tx-id error evidence]
    :as attrs}]
  (when-not (string? id)
    (throw (ex-info "path/id required" {:attrs attrs})))
  (when-not (string? actor)
    (throw (ex-info "actor required" {:attrs attrs})))
  (when-not (valid-phase? phase)
    (throw (ex-info "invalid phase" {:phase phase})))
  (cond-> {:path/id id
           :actor actor
           :phase phase
           :ts (now-ms)}
    (some? claim) (assoc :claim claim)
    (some? detail) (assoc :detail detail)
    (some? tx-id) (assoc :tx-id tx-id)
    (some? error) (assoc :error error)
    (some? evidence) (assoc :evidence evidence)))

(defn append-event
  "Append a proof-path event to a path, validating phase order.
   Returns updated path.

   Rules:
   - First event must be :clock-in
   - Subsequent events must follow the phase order exactly
  "
  [{:keys [events] :as path} ev]
  (let [phase (:phase ev)
        last-phase (some-> events last :phase)]
    (cond
      (nil? events)
      (throw (ex-info "path missing :events" {:path path}))

      (empty? events)
      (do
        (when-not (= phase :clock-in)
          (throw (ex-info "first event must be :clock-in" {:phase phase})))
        (update path :events conj ev))

      (not (next-phase? last-phase phase))
      (throw (ex-info "invalid phase order" {:last last-phase :next phase}))

      :else
      (update path :events conj ev))))

(defn complete?
  "True when the path ends with :clock-out." [{:keys [events]}]
  (= :clock-out (some-> events last :phase)))

(defn event-seq
  "Return the ordered list of phases for this path." [{:keys [events]}]
  (map :phase events))
