(ns charon.html
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.models :as models]
            [charon.utils :as utils]
            [lambdaisland.uri :as uri])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Document Entities$EscapeMode)))

(defn- append-meta [^Document doc page-id title]
  (let [title (-> (.createElement doc "title")
                  (.text title))
        page-id-meta (-> (.createElement doc "meta")
                         (.attr "name" "page-id")
                         (.attr "content" page-id)
                         (.text ""))]
    (-> (.head doc)
        (.appendChild title)
        (.appendChild page-id-meta))))

(defn- same-host? [href host confluence-url]
  (or (string/starts-with? href "/")
      (string/includes? confluence-url (str "://" host))))

(defn- a-attachment [a space-attachments confluence-url]
  (let [href (.attr a "href")
        {:keys [host path]} (uri/parse href)]
    (when (and path
               (string/starts-with? path "/download/attachments/")
               (same-host? href host confluence-url))
      (let [[page-id title & _] (-> path
                                    (string/replace-first "/download/attachments/" "")
                                    (string/split #"/"))
            attachment (utils/attachment page-id title)]
        (when (contains? space-attachments attachment)
          attachment)))))

(defn- a-page-title [a confluence-url id->title]
  (let [href (.attr a "href")
        {:keys [host path query]} (uri/parse href)
        {:keys [pageId]} (uri/query-string->map query nil)]
    (when (and path
               (string/ends-with? path "/viewpage.action")
               (same-host? href host confluence-url))
      (get id->title pageId))))

(defn- rewrite-a* [a space-attachments confluence-url id->title]
  (let [href (-> (.attr a "href")
                 string/trim)
        attachment (a-attachment a space-attachments confluence-url)
        title (a-page-title a confluence-url id->title)]
    (if-let [res (cond
                   ;; Blank link
                   (string/blank? href) href
                   ;; Page internal anchor
                   (string/starts-with? href "#") href
                   ;; Attachment
                   attachment (utils/attachment-filename attachment)
                   ;; Internal page
                   title (utils/filename title)
                   ;; Internal link
                   (string/starts-with? href "/") (str (uri/join confluence-url href))
                   ;; External link
                   (re-find #"^http(s)?://" href) href)]
      (do
        (.attr a "href" res)
        attachment)
      (log/infof "Unresolvable link: %s" href))))

(defn- rewrite-a [^Document doc pages space-attachments confluence-url]
  (let [id->title (utils/id->title pages)]
    (reduce
      (fn [res a]
        (if-let [attachment (rewrite-a* a space-attachments confluence-url id->title)]
          (conj res attachment)
          res))
      #{}
      (.select doc "a"))))

(defn- img-attachment [img page-id space-attachments]
  (let [class (.attr img "class")
        title (-> (.attr img "src")
                  uri/parse
                  :path
                  (string/split #"/")
                  last)]
    (when (string/includes? class "confluence-embedded-image")
      (let [attachment (utils/attachment page-id title)]
        (when (contains? space-attachments attachment)
          attachment)))))

(defn- rewrite-img* [img page-id space-attachments confluence-url]
  (let [src (-> (.attr img "src")
                string/trim)
        attachment (img-attachment img page-id space-attachments)]
    (if-let [res (cond
                   ;; Embedded attached image
                   attachment (utils/attachment-filename attachment)
                   ;; Internal image
                   (string/starts-with? src "/") (str (uri/join confluence-url src))
                   ;; External image
                   (re-find #"^http(s)?://" src) src)]
      (do
        (.attr img "src" res)
        attachment)
      (log/warnf "Unresolvable image: %s" src))))

(defn- rewrite-img [^Document doc page-id space-attachments confluence-url]
  (reduce
    (fn [res img]
      (if-let [attachment (rewrite-img* img page-id space-attachments confluence-url)]
        (conj res attachment)
        res))
    #{}
    (.select doc "img")))

;; TODO: Check if we can remove space-attachments from process parameters
(defn process
  "Parses HTML document, appends metadata and rewrites internal links in images and anchors.

  Returns a map containing the resulting HTML string and the referenced attachments."
  [content page-id title {:keys [confluence-url pages space-attachments]}]
  (let [doc (Jsoup/parseBodyFragment content)
        _ (-> (.outputSettings doc)
              (.escapeMode Entities$EscapeMode/base))
        _ (append-meta doc page-id title)
        attachments (set/union (rewrite-a doc pages space-attachments confluence-url)
                               (rewrite-img doc page-id space-attachments confluence-url))]
    (models/->ContentWithAttachments (.outerHtml doc) attachments)))
