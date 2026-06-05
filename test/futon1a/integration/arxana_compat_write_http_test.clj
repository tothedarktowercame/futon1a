(ns futon1a.integration.arxana-compat-write-http-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys]
            [xtdb.api :as xtdb])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files Path)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-xtdb-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(defn- http-client []
  (HttpClient/newHttpClient))

(defn- http-get-edn
  [client url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.GET)
                (.header "accept" "application/edn")
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(defn- http-post-edn
  [client url body-edn]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.POST (HttpRequest$BodyPublishers/ofString (pr-str body-edn)))
                (.header "content-type" "application/edn")
                (.header "accept" "application/edn")
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(defn- http-post-json
  [client url body]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.POST (HttpRequest$BodyPublishers/ofString (json/encode body)))
                (.header "content-type" "application/json")
                (.header "accept" "application/json")
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (json/decode (.body resp) true)}))

(defn- delete-recursively!
  [^String path]
  (let [p (.toPath (java.io.File. path))]
    (when (Files/exists p (make-array java.nio.file.LinkOption 0))
      ;; Delete children first, then parents.
      (with-open [stream (Files/walk p (make-array java.nio.file.FileVisitOption 0))]
        (->> (.iterator stream)
             (iterator-seq)
             (sort-by str #(compare %2 %1))
             (run! (fn [^Path x]
                     (Files/deleteIfExists x))))))))

(deftest arxana-compat-write-surface-json
  (testing "JSON compat endpoints used by futon4/arxana-store: hyperedge + snapshot save/restore"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        ;; Seed two endpoint entities via canonical /write (stable ids).
        (let [w (http-post-edn client (str base "/write")
                               {:penholder "tester"
                                :model {}
                                :identity nil
                                :tx-ops [[::xtdb/put {:xt/id "e-a" :entity/id "e-a" :entity/type :qa/thing}]
                                         [::xtdb/put {:xt/id "e-b" :entity/id "e-b" :entity/type :qa/thing}]]})]
          (is (= 200 (:status w))))

        ;; Hyperedge write via JSON.
        (let [resp (http-post-json client (str base "/api/alpha/hyperedge")
                                   {:penholder "tester"
                                    :hx/type "arxana/qa-hx"
                                    :hx/endpoints ["e-a" "e-b"]
                                    :props {:qa true}})]
          (is (= 200 (:status resp)))
          (is (= "arxana/qa-hx" (get-in resp [:body :hyperedge :hx/type]))))

        ;; Snapshot save to "latest" (JSON).
        (let [resp (http-post-json client (str base "/api/alpha/snapshot")
                                   {:scope "latest"
                                    :label "qa"})
              file (get-in resp [:body :snapshot/file])]
          (is (= 200 (:status resp)))
          (is (string? file))
          (is (.exists (java.io.File. file))))

        ;; Write a third entity AFTER the snapshot, so we can prove restore is a point-in-time view.
        (let [w (http-post-edn client (str base "/write")
                               {:penholder "tester"
                                :model {}
                                :identity nil
                                :tx-ops [[::xtdb/put {:xt/id "e-c" :entity/id "e-c" :entity/type :qa/thing}]]})]
          (is (= 200 (:status w))))
        (finally
          ((:stop! sys1))))

      ;; Simulate a fresh XTDB store by removing only the XTDB dirs, keeping snapshots/.
      (delete-recursively! (str dir "/tx-log"))
      (delete-recursively! (str dir "/doc-store"))
      (delete-recursively! (str dir "/index-store"))

      (let [sys2 (sys/start! {:data-dir dir
                              :port 0
                              :allowed-penholders #{"tester"}})
            base2 (str "http://127.0.0.1:" (:http/port sys2))]
        (try
          (let [r (http-get-edn client (str base2 "/entity/e-a"))]
            (is (= 404 (:status r))))

          (let [resp (http-post-json client (str base2 "/api/alpha/snapshot/restore")
                                     {:penholder "tester"
                                      :action "restore"
                                      :scope "latest"})]
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body :ok?]))))

          (let [r (http-get-edn client (str base2 "/entity/e-a"))]
            (is (= 200 (:status r)))
            (is (= :qa/thing (get-in r [:body :entity :entity/type]))))

          ;; e-c was written after the snapshot; it should not exist after restore.
          (let [r (http-get-edn client (str base2 "/entity/e-c"))]
            (is (= 404 (:status r))))
          (finally
            ((:stop! sys2))))))))

(deftest compat-entity-explicit-id-wins-over-same-name
  (testing "POST /api/alpha/entity writes the requested id even when name matches an existing entity"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [seed (http-post-edn client (str base "/api/alpha/entity")
                                  {:penholder "tester"
                                   :id "entity-Y"
                                   :name "shared-name"
                                   :type "test/entity"
                                   :props {:marker "original"}})]
          (is (= 200 (:status seed)))
          (is (= "entity-Y" (get-in seed [:body :entity :id]))))

        (let [explicit (http-post-edn client (str base "/api/alpha/entity")
                                      {:penholder "tester"
                                       :id "entity-X"
                                       :name "shared-name"
                                       :type "test/entity"
                                       :props {:marker "requested"}})]
          (is (= 200 (:status explicit)))
          (is (= "entity-X" (get-in explicit [:body :entity :id]))))

        (let [entity-x (http-get-edn client (str base "/api/alpha/entity/entity-X"))
              entity-y (http-get-edn client (str base "/api/alpha/entity/entity-Y"))]
          (is (= 200 (:status entity-x)))
          (is (= 200 (:status entity-y)))
          (is (= "requested" (get-in entity-x [:body :entity :props :marker])))
          (is (= "original" (get-in entity-y [:body :entity :props :marker]))))

        (finally
          ((:stop! sys1)))))))

(deftest compat-entity-idless-post-still-dedups-by-name
  (testing "POST /api/alpha/entity without :id keeps Futon1 name-dedup behavior"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [seed (http-post-edn client (str base "/api/alpha/entity")
                                  {:penholder "tester"
                                   :id "entity-existing"
                                   :name "idless-shared-name"
                                   :type "test/entity"})]
          (is (= 200 (:status seed)))
          (is (= "entity-existing" (get-in seed [:body :entity :id]))))

        (let [idless (http-post-edn client (str base "/api/alpha/entity")
                                    {:penholder "tester"
                                     :name "idless-shared-name"
                                     :type "test/entity"})]
          (is (= 200 (:status idless)))
          (is (= "entity-existing" (get-in idless [:body :entity :id]))))

        (finally
          ((:stop! sys1)))))))
