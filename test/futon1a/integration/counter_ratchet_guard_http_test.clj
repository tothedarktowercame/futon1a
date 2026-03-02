(ns futon1a.integration.counter-ratchet-guard-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-counter-ratchet-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(defn- http-client []
  (HttpClient/newHttpClient))

(defn- add-headers
  [builder headers]
  (reduce-kv (fn [b k v]
               (.header b
                        (if (keyword? k) (name k) (str k))
                        (str v)))
             builder
             headers))

(defn- http-edn
  [client method url & {:keys [body headers]}]
  (let [headers (merge {"accept" "application/edn"} (or headers {}))
        body-str (when (some? body) (pr-str body))
        headers (if (and body-str (= method "POST"))
                  (assoc headers "content-type" "application/edn")
                  headers)
        builder (HttpRequest/newBuilder (URI/create url))
        builder (add-headers builder headers)
        builder (case method
                  "GET" (.GET builder)
                  "POST" (.POST builder (HttpRequest$BodyPublishers/ofString (or body-str "")))
                  "DELETE" (.method builder "DELETE" (HttpRequest$BodyPublishers/noBody))
                  (.method builder method (HttpRequest$BodyPublishers/noBody)))
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(defn- write!
  [client base tx-ops]
  (http-edn client "POST" (str base "/write")
            :body {:penholder "tester"
                   :model {}
                   :identity nil
                   :tx-ops tx-ops}))

(deftest counter-ratchet-guards-protected-class-drops
  (testing "unexpected count drops are blocked for each protected write class"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (doseq [tx-ops [[[:xtdb.api/put {:xt/id "cr-entity-1"
                                         :entity/id "cr-entity-1"
                                         :entity/type :test/entity
                                         :entity/name "CR Entity"}]]
                        [[:xtdb.api/put {:xt/id "cr-relation-1"
                                         :relation/id "cr-relation-1"
                                         :relation/type :test/relation
                                         :relation/from "cr-entity-1"
                                         :relation/to "cr-entity-1"}]]
                        [[:xtdb.api/put {:xt/id "cr-descriptor-1"
                                         :entity/id "cr-descriptor-1"
                                         :entity/type :model/descriptor
                                         :model/scope :test/cr
                                         :schema/version "0.0.1"
                                         :schema/certificate {:penholder "tester"}
                                         :entities {}}]]
                        [[:xtdb.api/put {:xt/id "cr-docbook-1"
                                         :doc/id "cr-docbook-1"
                                         :doc/book "cr-book"
                                         :doc/title "CR Doc"}]]]]
          (let [resp (write! client base tx-ops)]
            (is (= 200 (:status resp)) (pr-str resp))
            (is (string? (get-in resp [:body :path/id])) (pr-str resp))))

        (doseq [[label doc-id] [[:entity "cr-entity-1"]
                                [:relation "cr-relation-1"]
                                [:descriptor "cr-descriptor-1"]
                                [:docbook "cr-docbook-1"]]]
          (let [resp (write! client base [[:xtdb.api/delete doc-id]])]
            (is (= 500 (:status resp)) (pr-str {:label label :resp resp}))
            (is (= 2 (get-in resp [:body :error :layer])) (pr-str {:label label :resp resp}))
            (is (= :counter-ratchet (get-in resp [:body :error :reason])) (pr-str {:label label :resp resp}))
            (is (= label (get-in resp [:body :error :context :label])) (pr-str {:label label :resp resp}))
            (is (= 1 (get-in resp [:body :error :context :prev])) (pr-str {:label label :resp resp}))
            (is (= 0 (get-in resp [:body :error :context :next])) (pr-str {:label label :resp resp})))

          (let [still-there (http-edn client "GET" (str base "/entity/" doc-id))]
            (is (= 200 (:status still-there)) (pr-str {:label label :still-there still-there}))))
        (finally
          ((:stop! sys1)))))))
