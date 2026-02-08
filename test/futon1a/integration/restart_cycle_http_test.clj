(ns futon1a.integration.restart-cycle-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys]
            [xtdb.api :as xtdb])
  (:import (java.net URI)
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

(deftest restart-cycle-over-http
  (testing "start -> write over HTTP -> stop -> restart -> read survives"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [h (http-get-edn client (str base "/health"))]
          (is (= 200 (:status h))))
        (let [w (http-post-edn client (str base "/write")
                               {:penholder "tester"
                                :model {}
                                :identity nil
                                :tx-ops [[::xtdb/put {:xt/id "e1" :entity/type :foo}]]})]
          (is (= 200 (:status w)))
          (is (string? (get-in w [:body :tx-id]))))
        (finally
          ((:stop! sys1))))
      (let [sys2 (sys/start! {:data-dir dir
                              :port 0
                              :allowed-penholders #{"tester"}})
            base2 (str "http://127.0.0.1:" (:http/port sys2))]
        (try
          (let [r (http-get-edn client (str base2 "/entity/e1"))]
            (is (= 200 (:status r)))
            (is (= :foo (get-in r [:body :entity :entity/type]))))
          (finally
            ((:stop! sys2))))))))
