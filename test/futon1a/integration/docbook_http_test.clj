(ns futon1a.integration.docbook-http-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [futon1a.system :as sys])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-docbook-"
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
                  "DELETE" (.method builder "DELETE" (HttpRequest$BodyPublishers/noBody))
                  (.method builder method (HttpRequest$BodyPublishers/noBody)))
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))
        body-str (.body resp)
        parsed (when (seq (str/trim (or body-str "")))
                 (json/decode body-str true))]
    {:status (.statusCode resp)
     :body parsed}))

(deftest docbook-json-roundtrip-and-ordering
  (testing "entry/toc/contents/heading/recent/order endpoints match Futon4 JSON expectations"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))
          book "futon3x"
          doc-id "futon3x-demo-doc"
          entry-id (str doc-id "::org")]
      (try
        (let [create (http-json client "POST" (str base "/api/alpha/docs/" book "/entry")
                                :body {"penholder" "tester"
                                       "doc_id" doc-id
                                       "entry_id" entry-id
                                       "title" "Demo heading"
                                       "outline_path" ["Demo" "Heading"]
                                       "path_string" "Demo / Heading"
                                       "version" "org"
                                       "timestamp" "2026-03-01T10:00:00Z"
                                       "body" "Demo body"})]
          (is (= 200 (:status create)))
          (is (= doc-id (get-in create [:body :doc-id])))
          (is (= entry-id (get-in create [:body :entry-id]))))

        (let [toc (http-json client "GET" (str base "/api/alpha/docs/" book "/toc"))]
          (is (= 200 (:status toc)))
          (is (= book (get-in toc [:body :book])))
          (is (= doc-id (get-in toc [:body :headings 0 :doc/id])))
          (is (= entry-id (get-in toc [:body :headings 0 :doc/latest :doc/entry-id]))))

        (let [contents (http-json client "GET" (str base "/api/alpha/docs/" book "/contents"))]
          (is (= 200 (:status contents)))
          (is (= doc-id (get-in contents [:body :headings 0 :doc/id])))
          (is (= entry-id (get-in contents [:body :headings 0 :doc/latest :doc/entry-id])))
          (is (= 1 (count (get-in contents [:body :headings 0 :doc/entries])))))

        (let [heading (http-json client "GET" (str base "/api/alpha/docs/" book "/heading/" doc-id))]
          (is (= 200 (:status heading)))
          (is (= doc-id (get-in heading [:body :doc/id])))
          (is (= entry-id (get-in heading [:body :doc/latest :doc/entry-id]))))

        (let [recent (http-json client "GET" (str base "/api/alpha/docs/" book "/recent?limit=5"))]
          (is (= 200 (:status recent)))
          (is (= book (get-in recent [:body :book])))
          (is (= entry-id (get-in recent [:body :entries 0 :doc/entry-id]))))

        (let [order (http-json client "POST" (str base "/api/alpha/docs/" book "/contents/order")
                               :body {"penholder" "tester"
                                      "order" [doc-id]
                                      "source" "docbook-test"
                                      "timestamp" "2026-03-01T10:05:00Z"})]
          (is (= 200 (:status order)))
          (is (= "ok" (get-in order [:body :status])))
          (is (= 1 (get-in order [:body :count]))))
        (finally
          ((:stop! sys1)))))))

(deftest docbook-batch-partial-accept-and-deletes
  (testing "batch writes accept valid entries even on 400, and delete endpoints work"
    (let [dir (temp-dir)
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))
          book "futon3x"
          keep-doc "futon3x-keep"
          keep-entry (str keep-doc "::org")
          kill-doc "futon3x-kill"
          kill-entry (str kill-doc "::org")]
      (try
        (let [batch (http-json client "POST" (str base "/api/alpha/docs/" book "/entries")
                               :body {"penholder" "tester"
                                      "entries" [{"doc_id" keep-doc
                                                  "entry_id" keep-entry
                                                  "title" "Keep"
                                                  "outline_path" ["Batch" "Keep"]
                                                  "path_string" "Batch / Keep"
                                                  "version" "org"
                                                  "timestamp" "2026-03-01T11:00:00Z"
                                                  "body" "Valid"}
                                                 {"doc_id" "broken-doc"
                                                  "title" "Broken"}]})]
          (is (= 400 (:status batch)))
          (is (= 2 (get-in batch [:body :count])))
          (is (= 1 (get-in batch [:body :accepted])))
          (is (= 1 (count (get-in batch [:body :errors])))))

        (let [kept (http-json client "GET" (str base "/api/alpha/docs/" book "/heading/" keep-doc))]
          (is (= 200 (:status kept)))
          (is (= keep-entry (get-in kept [:body :doc/latest :doc/entry-id]))))

        (let [seed-kill (http-json client "POST" (str base "/api/alpha/docs/" book "/entry")
                                   :body {"penholder" "tester"
                                          "doc_id" kill-doc
                                          "entry_id" kill-entry
                                          "title" "Kill"
                                          "outline_path" ["Batch" "Kill"]
                                          "path_string" "Batch / Kill"
                                          "version" "org"
                                          "timestamp" "2026-03-01T11:01:00Z"
                                          "body" "Delete me"})]
          (is (= 200 (:status seed-kill))))

        (let [del-toc (http-json client "DELETE"
                                 (str base "/api/alpha/docs/" book "/toc/" keep-doc "?cascade=true")
                                 :headers {"x-penholder" "tester"})]
          (is (= 200 (:status del-toc)))
          (is (= true (get-in del-toc [:body :cascade?])))
          (is (= 2 (get-in del-toc [:body :deleted]))))

        (let [missing (http-json client "GET" (str base "/api/alpha/docs/" book "/heading/" keep-doc))]
          (is (= 404 (:status missing))))

        (let [del-doc (http-json client "DELETE"
                                 (str base "/api/alpha/docs/" book "/doc/" kill-doc)
                                 :headers {"x-penholder" "tester"})]
          (is (= 200 (:status del-doc)))
          (is (= 2 (get-in del-doc [:body :deleted]))))

        (let [not-found (http-json client "DELETE"
                                   (str base "/api/alpha/docs/" book "/doc/" kill-doc)
                                   :headers {"x-penholder" "tester"})]
          (is (= 404 (:status not-found))))
        (finally
          ((:stop! sys1)))))))
