(ns futon1a.integration.path-id-mutating-http-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [futon1a.diag.proof-path :as proof]
            [futon1a.system :as sys]
            [xtdb.api :as xtdb])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-path-id-"
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

(defn- http-edn
  [client method url & {:keys [body headers]}]
  (let [headers (merge {"accept" "application/edn"} (or headers {}))
        body-str (when (some? body) (pr-str body))
        headers (if (and body-str (= method "POST"))
                  (assoc headers "content-type" "application/edn")
                  headers)
        builder (HttpRequest/newBuilder (URI/create url))
        builder (add-headers builder headers)
        builder (case method
                  "GET" (.GET builder)
                  "POST" (.POST builder (HttpRequest$BodyPublishers/ofString (or body-str "")))
                  "DELETE" (.method builder "DELETE" (HttpRequest$BodyPublishers/noBody))
                  (.method builder method (HttpRequest$BodyPublishers/noBody)))
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (edn/read-string (.body resp))}))

(defn- assert-path-id!
  [resp]
  (is (<= 200 (:status resp) 299) (pr-str resp))
  (let [path-id (get-in resp [:body :path/id])]
    (is (string? path-id) (pr-str resp))
    path-id))

(defn- read-proof-log
  [path]
  (let [p (.toPath (java.io.File. ^String path))]
    (if (Files/exists p (make-array java.nio.file.LinkOption 0))
      (->> (slurp path)
           (str/split-lines)
           (remove str/blank?)
           (mapv edn/read-string))
      [])))

(deftest mutating-http-endpoints-emit-path-id
  (testing "primary mutating endpoints return :path/id on success"
    (let [dir (temp-dir)
          proof-log-path (str dir "/proof-path-test.edn")
          events-path (str dir "/events.ndjson")
          recovery-json-path (str dir "/recovery.json")
          recovery-audio-dir (str dir "/audio")
          client (http-client)
          sys1 (sys/start! {:data-dir dir
                            :port 0
                            :proof-log-path proof-log-path
                            :allowed-penholders #{"tester"}})
          base (str "http://127.0.0.1:" (:http/port sys1))
          seen-path-ids (atom [])]
      (try
        (.mkdirs (java.io.File. recovery-audio-dir))
        (spit (str recovery-audio-dir "/page-1-path.mp3") "path-audio")
        (spit recovery-json-path
              (json/encode {:tracks [{:title "Path Song"
                                      :slug "page-1-path"
                                      :lyrics "Path lyrics"}]}))
        (spit events-path
              (str (json/encode {"edn" (pr-str {:type :entity/upsert
                                                :entity {:id "arxana/media-lyrics/misc/path-id-events"
                                                         :type :arxana/media-lyrics
                                                         :name "arxana/media-lyrics/misc/path-id-events"
                                                         :source "path events lyrics"
                                                         :media/sha256 "path-id-events"}})})
                   "\n"))

        (letfn [(record! [resp]
                  (when-let [pid (assert-path-id! resp)]
                    (swap! seen-path-ids conj pid)))]
        (let [w (http-edn client "POST" (str base "/write")
                          :body {:penholder "tester"
                                 :model {}
                                 :identity nil
                                 :tx-ops [[::xtdb/put {:xt/id "path-write-1"
                                                       :entity/id "path-write-1"
                                                       :entity/name "path-write-1"
                                                       :entity/type :test/entity}]]})]
          (record! w))

        (let [ca (http-edn client "POST" (str base "/entity")
                           :body {:penholder "tester"
                                  :name "compat-path-a"
                                  :type "test/entity"})
              cb (http-edn client "POST" (str base "/entity")
                           :body {:penholder "tester"
                                  :name "compat-path-b"
                                  :type "test/entity"})
              crel (http-edn client "POST" (str base "/relation")
                             :body {:penholder "tester"
                                    :type "test/compat-rel"
                                    :src "compat-path-a"
                                    :dst "compat-path-b"})]
          (record! ca)
          (record! cb)
          (record! crel))

        (let [ea (http-edn client "POST" (str base "/api/alpha/entity")
                           :body {:penholder "tester"
                                  :name "path-a"
                                  :type "test/entity"})
              eb (http-edn client "POST" (str base "/api/alpha/entity")
                           :body {:penholder "tester"
                                  :name "path-b"
                                  :type "test/entity"})
              eid-a (get-in ea [:body :entity :id])
              eid-b (get-in eb [:body :entity :id])]
          (record! ea)
          (record! eb)

          (let [rel (http-edn client "POST" (str base "/api/alpha/relation")
                              :body {:penholder "tester"
                                     :type "test/rel"
                                     :src eid-a
                                     :dst eid-b})]
            (record! rel))

          (let [batch (http-edn client "POST" (str base "/api/alpha/relations/batch")
                                :body {:penholder "tester"
                                       :relations [{:type "test/rel"
                                                    :src eid-a
                                                    :dst eid-b}]})]
            (record! batch))

          (let [hx (http-edn client "POST" (str base "/api/alpha/hyperedge")
                             :body {:penholder "tester"
                                    :hx/type "test/hx"
                                    :hx/endpoints [eid-a eid-b]})]
            (record! hx)))

        (let [ea (http-edn client "POST" (str base "/api/entity")
                           :body {:penholder "tester"
                                  :name "path-api-a"
                                  :type "test/entity"})
              eb (http-edn client "POST" (str base "/api/entity")
                           :body {:penholder "tester"
                                  :name "path-api-b"
                                  :type "test/entity"})
              eid-a (get-in ea [:body :entity :id])
              eid-b (get-in eb [:body :entity :id])]
          (record! ea)
          (record! eb)

          (let [rel (http-edn client "POST" (str base "/api/relation")
                              :body {:penholder "tester"
                                     :type "test/api-rel"
                                     :src eid-a
                                     :dst eid-b})]
            (record! rel))

          (let [batch (http-edn client "POST" (str base "/api/relations/batch")
                                :body {:penholder "tester"
                                       :relations [{:type "test/api-rel"
                                                    :src eid-a
                                                    :dst eid-b}]})]
            (record! batch))

          (let [hx (http-edn client "POST" (str base "/api/hyperedge")
                             :body {:penholder "tester"
                                    :hx/type "test/api-hx"
                                    :hx/endpoints [eid-a eid-b]})]
            (record! hx)))

        (let [lyrics (http-edn client "POST" (str base "/api/media/lyrics")
                               :body {:penholder "tester"
                                      :lyrics {:id "arxana/media-lyrics/path-id-abc"
                                               :name "path-id lyrics"
                                               :type "arxana/media-lyrics"
                                               :source "test source"
                                               :media/sha256 "abc"}})]
          (record! lyrics))

        (let [lyrics-alpha (http-edn client "POST" (str base "/api/alpha/media/lyrics")
                                     :body {:penholder "tester"
                                            :lyrics {:id "arxana/media-lyrics/path-id-def"
                                                     :name "path-id lyrics alpha"
                                                     :type "arxana/media-lyrics"
                                                     :source "test source alpha"
                                                     :media/sha256 "def"}})]
          (record! lyrics-alpha))

        (let [evidence (http-edn client "POST" (str base "/api/alpha/evidence")
                                 :body {:penholder "tester"
                                        :evidence/type :coordination
                                        :evidence/claim-type :observation
                                        :evidence/author "tester"
                                        :evidence/body {:status "ok"}})]
          (record! evidence))

        (let [ingest (http-edn client "POST" (str base "/ingest")
                               :body {:penholder "tester"
                                      :entities [{:entity/id "ingest-path-1"
                                                  :entity/type :test/entity}]
                                      :relations []
                                      :tx-ops [[::xtdb/put {:xt/id "ingest-path-1"
                                                            :entity/id "ingest-path-1"
                                                            :entity/type :test/entity
                                                            :entity/name "ingest-path-1"}]]})]
          (record! ingest))

        (let [snapshot (http-edn client "POST" (str base "/api/alpha/snapshot")
                                 :body {:scope "all"
                                        :label "path-id-gate"})
              sid (get-in snapshot [:body :snapshot/id])]
          (is (= 200 (:status snapshot)) (pr-str snapshot))
          (is (string? sid) (pr-str snapshot))
          (let [restore (http-edn client "POST" (str base "/api/alpha/snapshot/restore")
                                  :body {:penholder "tester"
                                         :scope "all"
                                         :snapshot/id sid})]
            (record! restore))
          (let [restore-api (http-edn client "POST" (str base "/api/snapshot/restore")
                                      :body {:penholder "tester"
                                             :scope "all"
                                             :snapshot/id sid})]
            (record! restore-api)))

        (let [session (http-edn client "POST" (str base "/api/alpha/lab/session")
                                :body {:penholder "tester"
                                       :lab/session-id "session-path-id"})]
          (record! session))

        (let [book "path-id-book"
              doc-id "path-id-doc"
              entry (http-edn client "POST" (str base "/api/alpha/docs/" book "/entry")
                              :body {:penholder "tester"
                                     :doc_id doc-id
                                     :entry_id (str doc-id "::org")
                                     :title "Path Doc"
                                     :outline_path ["Path" "Doc"]
                                     :path_string "Path / Doc"
                                     :version "org"
                                     :timestamp "2026-03-01T13:00:00Z"
                                     :body "Path body"})]
          (record! entry)
          (let [del (http-edn client "DELETE" (str base "/api/alpha/docs/" book "/doc/" doc-id)
                              :headers {"x-penholder" "tester"})]
            (record! del)))

        (let [book "path-id-book-toc"
              doc-id "path-id-doc-toc"
              entry (http-edn client "POST" (str base "/api/alpha/docs/" book "/entry")
                              :body {:penholder "tester"
                                     :doc_id doc-id
                                     :entry_id (str doc-id "::org")
                                     :title "Path Toc Doc"
                                     :outline_path ["Path" "Toc" "Doc"]
                                     :path_string "Path / Toc / Doc"
                                     :version "org"
                                     :timestamp "2026-03-01T13:05:00Z"
                                     :body "Path toc body"})]
          (record! entry)
          (let [del-toc (http-edn client "DELETE" (str base "/api/alpha/docs/" book "/toc/" doc-id "?cascade=true")
                                  :headers {"x-penholder" "tester"})]
            (record! del-toc)))

        (let [tp (http-edn client "POST" (str base "/api/alpha/types/parent")
                           :body {:penholder "tester"
                                  :type/id :test/path-id
                                  :type/kind :entity
                                  :type/parent :test})
              tm (http-edn client "POST" (str base "/api/alpha/types/merge")
                           :body {:penholder "tester"
                                  :type/id :test/path-id
                                  :type/kind :entity
                                  :type/aliases [:test/path-id-alias]})]
          (record! tp)
          (record! tm))

        (let [repair-legacy (http-edn client "POST" (str base "/__repair/legacy-descriptors")
                                      :body {:penholder "tester"})]
          (record! repair-legacy))

        (let [import-events (http-edn client "POST" (str base "/__repair/import-futon1-events-lyrics")
                                      :body {:penholder "tester"
                                             :events-path events-path})]
          (record! import-events))

        (let [import-recovery (http-edn client "POST" (str base "/__repair/import-lyrics-recovery")
                                        :body {:penholder "tester"
                                               :json-path recovery-json-path
                                               :audio-dir recovery-audio-dir})]
          (record! import-recovery))

        (let [entries (read-proof-log proof-log-path)
              expected-ids (set @seen-path-ids)
              logged-ids (set (keep :path/id entries))
              missing (vec (remove logged-ids expected-ids))]
          (is (pos? (count entries)))
          (is (= (count expected-ids) (count @seen-path-ids))
              (pr-str {:duplicate-path-ids @seen-path-ids}))
          (is (empty? missing)
              (pr-str {:expected (count expected-ids)
                       :logged (count logged-ids)
                       :missing missing}))
          (is (every? (fn [entry] (:ok? (proof/validate-complete-path entry))) entries)
              (pr-str {:invalid-count (count (remove (fn [entry] (:ok? (proof/validate-complete-path entry)))
                                                    entries))}))))
        (finally
          ((:stop! sys1)))))))
