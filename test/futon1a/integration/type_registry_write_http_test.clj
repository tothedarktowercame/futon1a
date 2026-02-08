(ns futon1a.integration.type-registry-write-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
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

(deftest type-registry-write-endpoints
  (testing "POST /types/merge and /types/parent write durable type docs"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [m (http-post-edn client (str base "/types/merge")
                               {:penholder "tester"
                                :type/id :person
                                :type/kind :entity
                                :type/aliases [:human]})]
          (is (= 200 (:status m)))
          (is (string? (get-in m [:body :tx-id])))
          (is (string? (get-in m [:body :path/id]))))

        (let [p (http-post-edn client (str base "/types/parent")
                               {:penholder "tester"
                                :type/id :project/chapter
                                :type/kind :entity
                                :type/parent :project})]
          (is (= 200 (:status p))))

        (let [t (http-get-edn client (str base "/types"))
              types (get-in t [:body :types])
              by-id (fn [kind id] (first (filter #(and (= kind (:type/kind %))
                                                       (= id (:type/id %)))
                                                 types)))
              person (by-id :entity :person)
              chapter (by-id :entity :project/chapter)]
          (is (= 200 (:status t)))
          (is (some? person))
          (is (= [:human] (:type/aliases person)))
          (is (some? chapter))
          (is (= :project (:type/parent chapter))))

        (finally
          ((:stop! sys1)))))))

