(ns futon1a.integration.relation-entity-id-endpoint-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-relation-id-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(defn- http-client []
  (HttpClient/newHttpClient))

(defn- http-edn
  [client method url & {:keys [body]}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "accept" "application/edn"))
        builder (if (= method "POST")
                  (-> builder
                      (.header "content-type" "application/edn")
                      (.POST (HttpRequest$BodyPublishers/ofString (pr-str body))))
                  (.GET builder))
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(defn- enc [s] (URLEncoder/encode s "UTF-8"))

(deftest relation-endpoints-resolve-entity-id-strings
  (testing "relation src/dst string endpoints may be existing :entity/id values"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [src (http-edn client "POST" (str base "/api/alpha/entity")
                            :body {:penholder "tester"
                                   :id "arxana/essay/test-v2"
                                   :name "Test Essay v2"
                                   :type "arxana/essay"})
              dst (http-edn client "POST" (str base "/api/alpha/entity")
                            :body {:penholder "tester"
                                   :id "arxana/essay/test-v1"
                                   :name "Test Essay v1"
                                   :type "arxana/essay"})]
          (is (= 200 (:status src)) (pr-str src))
          (is (= 200 (:status dst)) (pr-str dst))
          (let [rel (http-edn client "POST" (str base "/api/alpha/relation")
                              :body {:penholder "tester"
                                     :type "arxana/supersedes"
                                     :src "arxana/essay/test-v2"
                                     :dst "arxana/essay/test-v1"
                                     :props {:label "supersedes"}})]
            (is (= 200 (:status rel)) (pr-str rel))
            (is (= "arxana/essay/test-v2" (get-in rel [:body :relation :src-id])))
            (is (= "arxana/essay/test-v1" (get-in rel [:body :relation :dst-id]))))
          (let [ego (http-edn client "GET" (str base "/api/alpha/ego/" (enc "Test Essay v2")))
                outgoing (get-in ego [:body :ego :outgoing])]
            (is (= 200 (:status ego)) (pr-str ego))
            (is (some #(and (= :arxana/supersedes (get-in % [:relation :type]))
                            (= "arxana/essay/test-v1" (get-in % [:entity :id])))
                      outgoing))))
        (finally
          ((:stop! sys1)))))))

(deftest relation-endpoints-reject-unknown-strings
  (testing "unknown non-name non-id non-uuid strings are still rejected"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [dst (http-edn client "POST" (str base "/api/alpha/entity")
                            :body {:penholder "tester"
                                   :id "arxana/essay/known"
                                   :name "Known Essay"
                                   :type "arxana/essay"})
              rel (http-edn client "POST" (str base "/api/alpha/relation")
                            :body {:penholder "tester"
                                   :type "arxana/supersedes"
                                   :src "not an entity id"
                                   :dst "arxana/essay/known"})]
          (is (= 200 (:status dst)) (pr-str dst))
          (is (not= 200 (:status rel)) (pr-str rel)))
        (finally
          ((:stop! sys1)))))))
