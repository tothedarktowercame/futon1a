(ns futon1a.integration.hyperedge-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

(defn- temp-dir []
  (-> (Files/createTempDirectory "futon1a-hx-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(defn- http-client [] (HttpClient/newHttpClient))

(defn- http-get-edn [client url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.GET)
                (.header "accept" "application/edn")
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(defn- http-post-edn [client url body-edn]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.POST (HttpRequest$BodyPublishers/ofString (pr-str body-edn)))
                (.header "content-type" "application/edn")
                (.header "accept" "application/edn")
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(deftest hyperedge-write-and-read-roundtrip
  (testing "POST /api/alpha/hyperedge writes, GET reads back"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        ;; First create two entities so endpoints resolve
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester"
                                  :name "test-entity-a"
                                  :type "test/entity"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester"
                                  :name "test-entity-b"
                                  :type "test/entity"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])]
          (is (= 200 (:status e-a)))
          (is (= 200 (:status e-b)))

          ;; Write hyperedge with flat string endpoints
          (let [w (http-post-edn client (str base "/api/alpha/hyperedge")
                                 {:penholder "tester"
                                  :hx/type "link/refers-to"
                                  :hx/endpoints [eid-a eid-b]})]
            (is (= 200 (:status w)))
            (is (string? (get-in w [:body :tx-id])))
            (let [hx-id (get-in w [:body :hyperedge :hx/id])]
              (is (string? hx-id))
              (is (= :link/refers-to (get-in w [:body :hyperedge :hx/type])))

              ;; Read back by ID (URL-encode in case of special chars)
              (let [r (http-get-edn client (str base "/api/alpha/hyperedge/"
                                                (URLEncoder/encode hx-id "UTF-8")))]
                (is (= 200 (:status r)))
                (is (= :link/refers-to (get-in r [:body :hx/type])))
                (is (= [eid-a eid-b] (get-in r [:body :hx/endpoints])))))))

        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-rich-endpoints
  (testing "POST /api/alpha/hyperedge with Nelson-style endpoint maps"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester"
                                  :name "concept-intro"
                                  :type "arxana/article"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester"
                                  :name "concept-glossary"
                                  :type "arxana/article"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])]

          ;; Write with rich endpoint maps (futon1 Nelson style)
          (let [w (http-post-edn client (str base "/api/alpha/hyperedge")
                                 {:penholder "tester"
                                  :hx/type "link/refers-to"
                                  :hx/endpoints [{:role "source" :entity-id eid-a}
                                                 {:role "target" :entity-id eid-b :passage "section 3"}]
                                  :hx/content {:text "intro references glossary"}
                                  :hx/labels ["link/refers-to"]
                                  :hx/confidence 0.85})]
            (is (= 200 (:status w)))
            (let [hx (get-in w [:body :hyperedge])]
              (is (= :link/refers-to (:hx/type hx)))
              ;; Rich ends should be preserved
              (is (= 2 (count (:hx/ends hx))))
              (is (= :source (:role (first (:hx/ends hx)))))
              (is (= :target (:role (second (:hx/ends hx)))))
              (is (= "section 3" (:passage (second (:hx/ends hx)))))
              ;; Content and confidence preserved
              (is (= {:text "intro references glossary"} (:hx/content hx)))
              (is (= 0.85 (:hx/confidence hx))))))

        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-query-by-type
  (testing "GET /api/alpha/hyperedges?type=... returns matching hyperedges"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "e-a" :type "test/entity"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "e-b" :type "test/entity"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])
              ;; Write two hyperedges of different types
              _ (http-post-edn client (str base "/api/alpha/hyperedge")
                               {:penholder "tester"
                                :hx/type "link/supports"
                                :hx/endpoints [eid-a eid-b]})
              _ (http-post-edn client (str base "/api/alpha/hyperedge")
                               {:penholder "tester"
                                :hx/type "link/opposes"
                                :hx/endpoints [eid-a eid-b]})]

          ;; Query by type
          (let [r (http-get-edn client (str base "/api/alpha/hyperedges?type=link/supports"))]
            (is (= 200 (:status r)))
            (is (= 1 (get-in r [:body :count])))
            (is (= :link/supports (get-in r [:body :hyperedges 0 :hx/type]))))

          (let [r (http-get-edn client (str base "/api/alpha/hyperedges?type=link/opposes"))]
            (is (= 200 (:status r)))
            (is (= 1 (get-in r [:body :count])))))

        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-query-by-end
  (testing "GET /api/alpha/hyperedges?end=... returns hyperedges involving an entity"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "node-a" :type "test/entity"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "node-b" :type "test/entity"})
              e-c (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "node-c" :type "test/entity"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])
              eid-c (get-in e-c [:body :entity :id])
              ;; a->b and a->c
              _ (http-post-edn client (str base "/api/alpha/hyperedge")
                               {:penholder "tester"
                                :hx/type "link/supports"
                                :hx/endpoints [eid-a eid-b]})
              _ (http-post-edn client (str base "/api/alpha/hyperedge")
                               {:penholder "tester"
                                :hx/type "link/supports"
                                :hx/endpoints [eid-a eid-c]})]

          ;; Query by endpoint a — should find both
          (let [r (http-get-edn client (str base "/api/alpha/hyperedges?end=" eid-a))]
            (is (= 200 (:status r)))
            (is (= 2 (get-in r [:body :count]))))

          ;; Query by endpoint c — should find one
          (let [r (http-get-edn client (str base "/api/alpha/hyperedges?end=" eid-c))]
            (is (= 200 (:status r)))
            (is (= 1 (get-in r [:body :count])))))

        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-idempotent-write
  (testing "Writing the same hyperedge twice produces the same hx/id"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "idem-a" :type "test/entity"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "idem-b" :type "test/entity"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])
              payload {:penholder "tester"
                       :hx/type "link/supports"
                       :hx/endpoints [eid-a eid-b]}
              w1 (http-post-edn client (str base "/api/alpha/hyperedge") payload)
              w2 (http-post-edn client (str base "/api/alpha/hyperedge") payload)]
          (is (= 200 (:status w1)))
          (is (= 200 (:status w2)))
          (is (= (get-in w1 [:body :hyperedge :hx/id])
                 (get-in w2 [:body :hyperedge :hx/id]))))

        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-404-for-missing
  (testing "GET for nonexistent hyperedge returns 404"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [r (http-get-edn client (str base "/api/alpha/hyperedge/nonexistent-id"))]
          (is (= 404 (:status r))))
        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-api-alias
  (testing "POST /api/hyperedge works (futon4 arxana-store targets /api prefix)"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "alias-a" :type "test/entity"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "alias-b" :type "test/entity"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])
              ;; Use /api/hyperedge (the alias futon4 uses)
              w (http-post-edn client (str base "/api/hyperedge")
                               {:penholder "tester"
                                :hx/type "link/alias-test"
                                :hx/endpoints [eid-a eid-b]})]
          (is (= 200 (:status w)))
          (is (string? (get-in w [:body :hyperedge :hx/id]))))

        (finally
          ((:stop! sys1)))))))

(deftest hyperedge-multi-end
  (testing "Hyperedge with 3+ endpoints (true multi-end, not just binary)"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))]
      (try
        (let [e-a (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "multi-a" :type "test/entity"})
              e-b (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "multi-b" :type "test/entity"})
              e-c (http-post-edn client (str base "/api/alpha/entity")
                                 {:penholder "tester" :name "multi-c" :type "test/entity"})
              eid-a (get-in e-a [:body :entity :id])
              eid-b (get-in e-b [:body :entity :id])
              eid-c (get-in e-c [:body :entity :id])
              w (http-post-edn client (str base "/api/alpha/hyperedge")
                               {:penholder "tester"
                                :hx/type "hx/annotation"
                                :hx/endpoints [{:role "author" :entity-id eid-a}
                                               {:role "subject" :entity-id eid-b}
                                               {:role "topic" :entity-id eid-c}]
                                :hx/content {:text "multi-end test"}})]
          (is (= 200 (:status w)))
          (let [hx (get-in w [:body :hyperedge])]
            (is (= 3 (count (:hx/endpoints hx))))
            (is (= 3 (count (:hx/ends hx))))
            ;; All endpoints resolvable
            (is (every? :entity-id (:hx/ends hx)))
            ;; Roles preserved
            (is (= :author (:role (nth (:hx/ends hx) 0))))
            (is (= :subject (:role (nth (:hx/ends hx) 1))))
            (is (= :topic (:role (nth (:hx/ends hx) 2))))))

        (finally
          ((:stop! sys1)))))))
