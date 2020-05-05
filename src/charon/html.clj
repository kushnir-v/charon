(ns charon.html
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.utils :as utils]
            [lambdaisland.uri :as uri])
  (:import (org.jsoup Jsoup)
           (org.jsoup.nodes Document Entities$EscapeMode)))

(defn- append-meta [^Document doc id title]
  (let [title (-> (.createElement doc "title")
                  (.text title))
        page-id-meta (-> (.createElement doc "meta")
                         (.attr "name" "page-id")
                         (.attr "content" id)
                         (.text ""))]
    (-> (.head doc)
        (.appendChild title)
        (.appendChild page-id-meta))))

(defn- rewrite-a [^Document doc pages confluence-url]
  (let [confluence-url-host (:host (uri/parse confluence-url))
        id->title (utils/id->title pages)]
    (doseq [a (.select doc "a")]
      (let [{:keys [host path query]} (uri/parse (.attr a "href"))
            {:keys [pageId]} (uri/query-string->map query nil)
            title (get id->title pageId)]
        (when (and path
                   (string/ends-with? path "/viewpage.action")
                   (= confluence-url-host host))
          (if title
            (.attr a "href" (utils/filename title))
            (log/warnf "Unresolvable link: %s" (.outerHtml a))))))))

(defn- rewrite-img [^Document doc id attachments]
  (let [rewritable? (fn [img] (let [class (.attr img "class")]
                                (string/includes? class "confluence-embedded-image")))]
    (doseq [img (->> (.select doc "img")
                     (filter rewritable?))]
      (let [title (-> (.attr img "src")
                      uri/parse
                      :path
                      (string/split #"/")
                      last)
            attachment (utils/attachment id title)]
        (if (contains? attachments attachment)
          (.attr img "src" (utils/attachment-filename attachment))
          (log/warnf "Unresolvable image: %s" (.outerHtml img)))))))

(defn process
  "Converts HTML document to XHTML, appends some metadata and processes internal links."
  [content id title attachments pages confluence-url]
  (let [doc (Jsoup/parseBodyFragment content)
        _ (-> (.outputSettings doc)
              (.escapeMode Entities$EscapeMode/base))]
    (append-meta doc id title)
    (rewrite-a doc pages confluence-url)
    (rewrite-img doc id attachments)
    (.outerHtml doc)))
