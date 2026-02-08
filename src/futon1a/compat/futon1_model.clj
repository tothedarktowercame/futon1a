(ns futon1a.compat.futon1-model
  "Compatibility shapes for Futon1 /meta/model endpoints."
  (:require [clojure.string :as str]
            [futon1a.model.descriptor-store :as dstore])
  (:import (java.security MessageDigest)
           (java.util Base64)))

(defn- normalize-for-hash [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]] [k (normalize-for-hash v)]))
          value)

    (set? value)
    (vec (sort-by pr-str (map normalize-for-hash value)))

    (sequential? value)
    (mapv normalize-for-hash value)

    :else value))

(defn descriptor-hash
  "SHA-256 base64 hash of a descriptor map, using a stable normalization."
  [descriptor]
  (let [normalized (normalize-for-hash descriptor)
        payload (pr-str normalized)
        digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes payload "UTF-8"))]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- kw-scope [scope]
  (cond
    (keyword? scope) scope
    (string? scope) (let [s (str/trim scope)
                          s (if (str/starts-with? s ":") (subs s 1) s)]
                      (when (seq s) (keyword s)))
    :else nil))

(defn describe-scope
  "Return Futon1-style {:descriptor ... :hash ...} for a scope keyword."
  [node scope]
  (let [scope (kw-scope scope)
        desc (when scope (dstore/get-descriptor node scope))]
    (when desc
      {:descriptor desc
       :hash (descriptor-hash desc)
       :schema/version (:schema/version desc)
       :client/schema-min (:client/schema-min desc)})))

