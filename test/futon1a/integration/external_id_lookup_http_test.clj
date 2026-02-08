(ns futon1a.integration.external-id-lookup-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys]
            [xtdb.api :as xtdb])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.util UUID)))

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

(deftest lookup-by-external-id-over-http
  (testing "write with identity -> lookup by (source, external-id)"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [eid (str (UUID/randomUUID))
              source "s1"
              ext "ext-1"
              w (http-post-edn client (str base "/write")
                               {:penholder "tester"
                                :model {}
                                :identity {:source source :external-id ext}
                                :tx-ops [[::xtdb/put {:xt/id eid
                                                      :entity/type :foo
                                                      :entity/source source
                                                      :entity/external-id ext}]]})]
          (is (= 200 (:status w)))
          (let [r (http-get-edn client (str base "/entity?source=" source "&external-id=" ext))]
            (is (= 200 (:status r)))
            (is (= eid (get-in r [:body :entity :xt/id])))))

        (testing "conflicting write is rejected (identity uniqueness)"
          (let [eid1 (str (UUID/randomUUID))
                eid2 (str (UUID/randomUUID))
                source "s2"
                ext "ext-2"
                w1 (http-post-edn client (str base "/write")
                                  {:penholder "tester"
                                   :model {}
                                   :identity {:source source :external-id ext}
                                   :tx-ops [[::xtdb/put {:xt/id eid1 :entity/type :foo}]]})
                w2 (http-post-edn client (str base "/write")
                                  {:penholder "tester"
                                   :model {}
                                   :identity {:source source :external-id ext}
                                   :tx-ops [[::xtdb/put {:xt/id eid2 :entity/type :foo}]]})]
            (is (= 200 (:status w1)))
            (is (= 409 (:status w2)))
            (is (= 1 (get-in w2 [:body :error :layer])))
            (is (= :external-id-conflict (get-in w2 [:body :error :reason])))))

        (testing "ambiguity detection on lookup"
          (let [source "s3"
                ext "ext-3"
                w (http-post-edn client (str base "/write")
                                 {:penholder "tester"
                                  :model {}
                                  :identity nil
                                  :tx-ops [[::xtdb/put {:xt/id "corrupt-idx-1"
                                                        :identity/source source
                                                        :identity/external-id ext
                                                        :identity/entity-id (str (UUID/randomUUID))}]
                                          [::xtdb/put {:xt/id "corrupt-idx-2"
                                                        :identity/source source
                                                        :identity/external-id ext
                                                        :identity/entity-id (str (UUID/randomUUID))}]]})]
            (is (= 200 (:status w)))
            (let [r (http-get-edn client (str base "/entity?source=" source "&external-id=" ext))]
              (is (= 409 (:status r)))
              (is (= 1 (get-in r [:body :error :layer])))
              (is (= :external-id-ambiguous (get-in r [:body :error :reason]))))))

        (finally
          ((:stop! sys1)))))))
