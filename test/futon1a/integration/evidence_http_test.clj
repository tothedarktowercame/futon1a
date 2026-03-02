(ns futon1a.integration.evidence-http-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-evidence-"
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

(defn- http-json
  [client method url & {:keys [body headers]}]
  (let [headers (merge {"accept" "application/json"} (or headers {}))
        payload (when (some? body) (json/encode body))
        headers (if (and payload (= method "POST"))
                  (assoc headers "content-type" "application/json")
                  headers)
        builder (HttpRequest/newBuilder (URI/create url))
        builder (add-headers builder headers)
        builder (case method
                  "GET" (.GET builder)
                  "POST" (.POST builder (HttpRequest$BodyPublishers/ofString (or payload "")))
                  (.method builder method (HttpRequest$BodyPublishers/noBody)))
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))
        body-str (.body resp)
        parsed (when (seq body-str) (json/decode body-str true))]
    {:status (.statusCode resp)
     :body parsed}))

(deftest evidence-write-emits-proof-path-id
  (testing "POST /api/alpha/evidence writes through pipeline and returns :path/id"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [resp (http-json client "POST" (str base "/api/alpha/evidence")
                              :body {"penholder" "tester"
                                     "evidence/type" "coordination"
                                     "evidence/claim-type" "observation"
                                     "evidence/author" "tester"
                                     "evidence/subject" {"ref/type" "mission"
                                                         "ref/id" "peripheral-gauntlet"}
                                     "evidence/body" {"status" "in-progress"}})
              eid (get-in resp [:body :evidence/id])]
          (is (= 201 (:status resp)))
          (is (= true (get-in resp [:body :ok])))
          (is (string? (get-in resp [:body :tx-id])))
          (is (string? (get-in resp [:body :path/id])))
          (is (string? eid))
          (is (= "tester" (get-in resp [:body :entry :evidence/author])))

          (let [listing (http-json client "GET" (str base "/api/alpha/evidence?limit=5"))]
            (is (= 200 (:status listing)))
            (is (= eid (get-in listing [:body :entries 0 :evidence/id])))))
        (finally
          ((:stop! sys1)))))))

(deftest evidence-duplicate-id-still-conflicts
  (testing "duplicate evidence id returns 409"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))
          eid "evidence-dup-1"
          payload {"penholder" "tester"
                   "evidence/id" eid
                   "evidence/type" "coordination"
                   "evidence/claim-type" "observation"
                   "evidence/author" "tester"
                   "evidence/body" {"note" "dup"}}]
      (try
        (let [first-write (http-json client "POST" (str base "/api/alpha/evidence") :body payload)
              second-write (http-json client "POST" (str base "/api/alpha/evidence") :body payload)]
          (is (= 201 (:status first-write)))
          (is (string? (get-in first-write [:body :path/id])))
          (is (= 409 (:status second-write)))
          (is (= eid (get-in second-write [:body :evidence/id]))))
        (finally
          ((:stop! sys1)))))))
