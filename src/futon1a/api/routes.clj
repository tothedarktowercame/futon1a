(ns futon1a.api.routes
  "Minimal API surface for futon1a (stub)."
  (:require [futon1a.api.errors :as errors]))

(defn ok
  [body]
  {:status 200
   :body body})

(defn health
  "Simple health endpoint." []
  (ok {:status "ok"}))

(defn handle-error
  [e]
  (errors/error->response (ex-data e)))
