(ns futon1a.integration.futon1-api-parity-read-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys]
            [xtdb.api :as xtdb])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

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

(defn- enc [s] (URLEncoder/encode s "UTF-8"))

(deftest futon1-parity-read-surface
  (testing "/api/alpha entity + ego + entities/latest shapes used by futon3"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        ;; Seed a minimal pattern library entity plus a component and a sigil with outgoing links.
        (let [tx (http-post-edn client (str base "/write")
                                {:penholder "tester"
                                 :model {}
                                 :identity nil
                                 :tx-ops [[::xtdb/put {:xt/id "p1"
                                                       :entity/id "p1"
                                                       :entity/name "p1"
                                                       :entity/type :pattern/library
                                                       :entity/source "/futon3/library/p1.flexiarg"}]
                                          [::xtdb/put {:xt/id "c1"
                                                       :entity/id "c1"
                                                       :entity/name "c1"
                                                       :entity/type :pattern/component
                                                       :entity/source "/futon3/library/c1.flexiarg"}]
                                          [::xtdb/put {:xt/id "s1"
                                                       :entity/id "s1"
                                                       :entity/name "s1"
                                                       :entity/type :pattern/sigil
                                                       :entity/source "futon3/sigil"}]
                                          [::xtdb/put {:xt/id "r1"
                                                       :relation/id "r1"
                                                       :relation/type :pattern/includes
                                                       :relation/src "p1"
                                                       :relation/dst "c1"
                                                       :relation/provenance {:note ":pattern/includes"}}]
                                          [::xtdb/put {:xt/id "r2"
                                                       :relation/id "r2"
                                                       :relation/type :pattern/has-sigil
                                                       :relation/src "p1"
                                                       :relation/dst "s1"}]]})]
          (is (= 200 (:status tx))))

        (let [resp (http-get-edn client (str base "/api/alpha/entities/latest?type=pattern/library&limit=10"))]
          (is (= 200 (:status resp)))
          (is (= "pattern/library" (get-in resp [:body :type])))
          (is (= #{"p1"} (set (map :name (get-in resp [:body :entities]))))))

        (let [resp (http-get-edn client (str base "/api/alpha/entity/" (enc "p1")))
              entity (get-in resp [:body :entity])]
          (is (= 200 (:status resp)))
          (is (= "p1" (:name entity)))
          (is (= :pattern/library (:type entity))))

        (let [resp (http-get-edn client (str base "/api/alpha/ego/" (enc "p1")))
              outgoing (get-in resp [:body :ego :outgoing])
              types (set (map #(get-in % [:relation :type]) outgoing))
              includes (filter #(= :pattern/includes (get-in % [:relation :type])) outgoing)]
          (is (= 200 (:status resp)))
          (is (contains? types :pattern/includes))
          (is (contains? types :pattern/has-sigil))
          (is (seq includes))
          (is (seq (get-in (first includes) [:entity :source]))))

        (finally
          ((:stop! sys1)))))))

(deftest futon1-parity-duplicate-by-name-handling
  (testing "/api/alpha/entity resolves duplicates deterministically (no 404 on ambiguity)"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        ;; Seed two entities with the same :entity/name and type.
        (let [tx (http-post-edn client (str base "/write")
                                {:penholder "tester"
                                 :model {}
                                 :identity nil
                                 :tx-ops [[::xtdb/put {:xt/id "a"
                                                       :entity/id "a"
                                                       :entity/name "dup"
                                                       :entity/type :pattern/library
                                                       :entity/source "one"}]
                                          [::xtdb/put {:xt/id "b"
                                                       :entity/id "b"
                                                       :entity/name "dup"
                                                       :entity/type :pattern/library
                                                       :entity/source "two"}]]})]
          (is (= 200 (:status tx))))

        ;; fetch-entity must not return 404 just because more than one match exists.
        (let [resp (http-get-edn client (str base "/api/alpha/entity/" (enc "dup")))
              entity (get-in resp [:body :entity])]
          (is (= 200 (:status resp)))
          (is (= "dup" (:name entity)))
          ;; Canonical rule is lexical-min id.
          (is (= "a" (:id entity))))

        (finally
          ((:stop! sys1)))))))
