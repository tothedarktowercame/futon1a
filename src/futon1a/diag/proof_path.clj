(ns futon1a.diag.proof-path
  "Proof-path event logging scaffold (Part II, 2.1).

   Pure data model for ordered proof-path events. Storage and IO are handled
   by callers; this module only validates and constructs event maps."
  (:require [clojure.java.io :as io])
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

(def ^:private event-schema
  "Minimal schema for a proof-path event. Keys are required unless noted."
  {:required {:path/id string?
              :actor string?
              :phase keyword?
              :ts integer?}
   :optional {:claim (constantly true)
              :detail (constantly true)
              :tx-id string?
              :error map?
              :evidence map?}})

(def ^:private path-schema
  "Minimal schema for a proof-path container."
  {:required {:path/id string?
              :path/created-at integer?
              :events vector?}})

(def ^:private error-codes
  #{:proof/invalid-event
    :proof/invalid-path
    :proof/invalid-order
    :proof/incomplete-path})

(defn- now-ms [] (System/currentTimeMillis))

(defn- uuid [] (str (UUID/randomUUID)))

(defn valid-phase? [phase]
  (contains? phase->index phase))

(defn next-phase? [prev next]
  (and (valid-phase? prev)
       (valid-phase? next)
       (= (inc (phase->index prev)) (phase->index next))))

(defn- error
  [code message context]
  (when-not (contains? error-codes code)
    (throw (ex-info "unknown proof-path error code" {:code code})))
  {:error/code code
   :error/message message
   :error/context context})

(defn new-path
  "Create a new, empty proof-path log." []
  {:path/id (uuid)
   :path/created-at (now-ms)
   :events []})

(defn validate-event
  "Validate event against the minimal schema.
   Returns {:ok? true} or {:ok? false :errors [...]}"
  [ev]
  (let [{:keys [required optional]} event-schema
        required-errors (->> required
                             (keep (fn [[k pred]]
                                     (when-not (pred (get ev k))
                                       {:field k :reason :invalid-or-missing})))
                             vec)
        optional-errors (->> optional
                             (keep (fn [[k pred]]
                                     (when (contains? ev k)
                                       (when-not (pred (get ev k))
                                         {:field k :reason :invalid}))))
                             vec)
        phase-errors (when-not (valid-phase? (:phase ev))
                       [{:field :phase :reason :invalid-phase}])
        errors (vec (concat required-errors optional-errors (or phase-errors [])))]
    (if (seq errors)
      {:ok? false :errors errors}
      {:ok? true})))

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

(defn validate-path
  "Validate a full path:
   - path container matches schema
   - each event passes schema
   - event phases are strictly ordered
   Returns {:ok? true} or {:ok? false :errors [...]}"
  [{:keys [events] :as path}]
  (let [container-errors (->> (:required path-schema)
                              (keep (fn [[k pred]]
                                      (when-not (pred (get path k))
                                        {:field k :reason :invalid-or-missing})))
                              vec)]
    (if (seq container-errors)
      {:ok? false :errors container-errors}
      (let [event-errors (->> events
                              (map-indexed
                               (fn [idx ev]
                                 (let [res (validate-event ev)]
                                   (when-not (:ok? res)
                                     {:index idx :errors (:errors res)}))))
                              (remove nil?)
                              vec)
            phase-errors (->> events
                              (partition 2 1)
                              (map-indexed
                               (fn [idx [a b]]
                                 (when-not (next-phase? (:phase a) (:phase b))
                                   {:index idx
                                    :reason :invalid-phase-order
                                    :from (:phase a)
                                    :to (:phase b)})))
                              (remove nil?)
                              vec)
            errors (vec (concat event-errors phase-errors))]
        (if (seq errors)
          {:ok? false :errors errors}
          {:ok? true})))))

(defn complete?
  "True when the path ends with :clock-out." [{:keys [events]}]
  (= :clock-out (some-> events last :phase)))

(defn event-seq
  "Return the ordered list of phases for this path." [{:keys [events]}]
  (map :phase events))

(defn validate-complete-path
  "Validate that a path is well-formed and complete (full phase sequence).
   Returns {:ok? true} or {:ok? false :errors [...]}"
  [path]
  (let [base (validate-path path)
        phases (vec (event-seq path))]
    (cond
      (not (:ok? base))
      base

      (not= phases proof-path-phases)
      {:ok? false
       :errors [(error :proof/incomplete-path
                       "proof path does not contain full phase sequence"
                       {:expected proof-path-phases :actual phases})]}

      :else
      {:ok? true})))

(defn path->edn
  "Serialize a path to EDN string." [path]
  (pr-str path))

(defn append-edn!
  "Append a single EDN-encoded path to a file as one line.
   Creates parent directories if needed. Returns the output path." [path out-file]
  (let [file (io/file out-file)]
    (io/make-parents file)
    (spit file (str (path->edn path) "\n") :append true)
    (.getPath file)))
