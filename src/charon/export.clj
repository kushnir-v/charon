(ns charon.export
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.structure :as structure]
            [charon.html :as html]
            [charon.utils :as utils]
            [hiccup.core :as h]
            [perseverance.core :refer [retry progressive-retry-strategy]]
            [slingshot.slingshot :refer [throw+]])
  (:import (clojure.lang ExceptionInfo)))

;; TODO: Retries
;; TODO: Memory footprint?
;; TODO: Too many options passed here and there. Extract them in a state?
;; TODO: REPL workflow

(defn- check-config [{:keys [confluence-url output space] :as m}]
  (if (some nil? [confluence-url output space])
    (throw+ {:type    ::invalid-opts
             :message "Some required options are nil"
             :context m})
    m))

(def content-request-limit 25)
(def content-request-expand (string/join "," ["ancestors" "body.export_view" "children.page" "children.attachment"]))

(defn- get-pages [{:keys [confluence-url space] :as config}]
  (let [url (format "%s/rest/api/space/%s/content" confluence-url space)]
    (loop [start 0 ret (list)]
      (log/infof "Downloading %d pages starting from: %d" content-request-limit start)
      (let [query-params {:type "page" :start start :limit content-request-limit :expand content-request-expand}
            body (utils/get-json url {:accept :json :query-params query-params} config)
            next-path (get-in body [:page :_links :next])
            next-ret (lazy-cat ret (get-in body [:page :results] (list)))]
        (if-not next-path
          next-ret
          (let [next-start (+ start content-request-limit)]
            (recur next-start next-ret)))))))

(defn- attachment->url
  "Returns a set of all attachments in the confluence space."
  [pages confluence-url]
  (let [attachment-url (fn [a] (let [title (:title a)
                                     download (get-in a [:_links :download])
                                     page-id (-> (get-in a [:_expandable :container])
                                                 (string/split #"/")
                                                 last)
                                     url (str confluence-url download)]
                                 [(utils/attachment page-id title) url]))]
    (->> pages
         (mapcat #(get-in % [:children :attachment :results]))
         (map attachment-url)
         (into {}))))

(defn- write-pages
  "Processes pages, writes them to disc and returns a set of referenced attachments."
  [pages space-attachments {:keys [output confluence-url]}]
  (reduce
    (fn [res p]
      (let [{:keys [id title]} p
            {:keys [content attachments]} (-> (get-in p [:body :export_view :value])
                                              (html/process id title pages space-attachments confluence-url))
            f (utils/filename output title)]
        (utils/write-file f content)
        (set/union res attachments)))
    #{}
    pages))

(defn- write-toc [toc output]
  (utils/write-file (str output "/toc.xml") (h/html toc)))

(defn- download-attachments [attachments attachment->url {:keys [output] :as config}]
  (let [_ (->> attachments
               (pmap (fn [attachment]
                       (let [f (utils/attachment-filename output attachment)
                             url (get attachment->url attachment)]
                         (utils/download-file url f config))))
               (doall))]
    (shutdown-agents)))

(defn- log-retry
  "Prints a message to stdout that an error happened and going to be retried."
  [wrapped-ex attempt delay]
  (log/infof "%s, retrying in %.1f seconds... (%d)"
             (:e (ex-data wrapped-ex))
             (/ delay 1000.0)
             attempt))

(defn- tags-in? [coll]
  (fn [e] (and (instance? ExceptionInfo e)
               (contains? coll (:tag (ex-data e))))))

(defn- export [{:keys [confluence-url space page output] :as config}]
  (if page
    (log/infof "Exporting Confluence page %s - %s from: %s" space page confluence-url)
    (log/infof "Exporting Confluence space %s from: %s" space confluence-url))

  (retry {:strategy (progressive-retry-strategy :max-count 3 :initial-delay 10000)
          :selector (tags-in? #{::utils/http-request ::utils/download-file})
          :log-fn   log-retry}
    (let [space-pages (get-pages config)
          attachment->url (attachment->url space-pages confluence-url)
          space-attachments (set (keys attachment->url))
          pages (if page (structure/subtree-pages space-pages page) space-pages)
          attachments (write-pages pages space-attachments config)]
      ;; If --page-url is provided, download only referenced attachments.
      (download-attachments
        (if page attachments space-attachments) attachment->url config)
      (write-toc (structure/toc-html pages) output))))

(defn run [options]
  (-> (check-config options)
      utils/make-output-dirs
      export))
