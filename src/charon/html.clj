(ns charon.html
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.utils :as utils])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Document Entities$EscapeMode)
           (java.net URL)))

(defn- rewrite-img-links [^Document doc id attachments]
  (let [rewritable? (fn [img] (let [class (.attr img "class")]
                                (string/includes? class "confluence-embedded-image")))]
    (doseq [img (->> (.select doc "img")
                     (filter rewritable?))]
      (let [title (-> (.attr img "src")
                      URL.
                      .getPath
                      (string/split #"/")
                      last)
            attachment (utils/attachment id title)]
        (if (contains? attachments attachment)
          (.attr img "src" (utils/attachment-filename attachment))
          (log/warnf "Unresolvable image: %s" (.outerHtml img))))))
  doc)

(defn- append-meta [^Document doc id title]
  (let [title (-> (.createElement doc "title")
                  (.text title))
        page-id-meta (-> (.createElement doc "meta")
                         (.attr "name" "page-id")
                         (.attr "content" id)
                         (.text ""))]
    (-> (.head doc)
        (.appendChild title)
        (.appendChild page-id-meta)))
  doc)

(defn process
  "Converts HTML document to XHTML, appends some metadata and processes internal links."
  [content id title attachments]
  (let [doc (Jsoup/parseBodyFragment content)
        _ (-> (.outputSettings doc)
              (.escapeMode Entities$EscapeMode/xhtml))]

    (-> doc
        (append-meta id title)
        (rewrite-img-links id attachments)
        .outerHtml)))
