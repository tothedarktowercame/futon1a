(ns futon1a.http.app-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [futon1a.http.app :as app])
  (:import (java.nio.file Files)))

(defn- temp-dir
  []
  (-> (Files/createTempDirectory "futon1a-http-app-"
                                 (make-array java.nio.file.attribute.FileAttribute 0))
      (.toFile)
      (.getAbsolutePath)))

(deftest wrap-static-serves-directory-index-as-html
  (testing "trailing-slash static routes serve index.html with text/html content type"
    (let [dir (temp-dir)
          asset-dir (str dir "/evidence-viewer")
          _ (.mkdirs (java.io.File. asset-dir))
          _ (spit (str asset-dir "/index.html") "<!DOCTYPE html><title>Evidence Landscape</title>")
          handler (app/wrap-static (fn [_] {:status 404
                                            :headers {}
                                            :body "not-found"})
                                   dir)
          resp (handler {:request-method :get
                         :uri "/evidence-viewer/"})
          headers (:headers resp)
          content-type (or (get headers "Content-Type")
                           (get headers "content-type"))]
      (is (= 200 (:status resp)))
      (is (string? content-type))
      (is (str/starts-with? content-type "text/html")))))
