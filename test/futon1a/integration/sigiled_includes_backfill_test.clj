(ns futon1a.integration.sigiled-includes-backfill-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.scripts.backfill-sigiled-patterns-includes :as backfill]
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

(deftest backfill-adds-summary-includes-for-sigiled-patterns
  (testing "sigiled pattern/library without includes gets a deterministic summary component"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        ;; Seed a pattern + sigil edge but no includes.
        (let [tx (http-post-edn client (str base "/write")
                                {:penholder "tester"
                                 :model {}
                                 :identity nil
                                 :tx-ops [[::xtdb/put {:xt/id "p1"
                                                       :entity/id "p1"
                                                       :entity/name "p1"
                                                       :entity/type :pattern/library
                                                       :entity/source "pattern-source"}]
                                          [::xtdb/put {:xt/id "s1"
                                                       :entity/id "s1"
                                                       :entity/name "sigil/one"
                                                       :entity/type :pattern/sigil
                                                       :entity/source "sigil-source"}]
                                          [::xtdb/put {:xt/id "r1"
                                                       :relation/id "r1"
                                                       :relation/type :pattern/has-sigil
                                                       :relation/src "p1"
                                                       :relation/dst "s1"}]]})]
          (is (= 200 (:status tx))))

        (let [result (backfill/backfill! {:node (:node sys1)
                                          :store (:store sys1)
                                          :penholder "tester"
                                          :allowed-penholders #{"tester"}})]
          (is (= true (:ok? result)))
          (is (= 1 (:updated result))))

        (let [ego (http-get-edn client (str base "/api/alpha/ego/" (enc "p1")))
              outgoing (get-in ego [:body :ego :outgoing])
              includes (filter #(= :pattern/includes (get-in % [:relation :type])) outgoing)]
          (is (= 200 (:status ego)))
          (is (seq includes))
          (is (seq (get-in (first includes) [:entity :source]))))

        (finally
          ((:stop! sys1)))))))

