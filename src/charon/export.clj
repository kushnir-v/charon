(ns charon.export
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.html :as html]
            [charon.toc :as toc]
            [charon.utils :as utils]
            [hiccup.core :as h]
            [perseverance.core :as perseverance]
            [slingshot.slingshot :refer [throw+]]))

;; TODO: Retries
;; TODO: Memory footprint?

(defn- check-config [{:keys [confluence-url output space] :as m}]
  (if (some nil? [confluence-url output space])
    (throw+ {:type    ::invalid-opts
             :message "Some required options are nil"
             :context m})
    m))

(def content-request-limit 25)
(def content-request-expand (string/join "," ["ancestors" "body.export_view" "children.page" "children.attachment"]))

(defn- ancestor-or-self= [title]
  (if title
    (fn [page] (let [titles (->> (:ancestors page)
                                 (map :title)               ;; Ancestors titles
                                 (into #{(:title page)}))]  ;; Self title
                 (contains? titles title)))
    (constantly true)))

(defn- get-pages [{:keys [confluence-url space page] :as config}]
  (let [url (format "%s/rest/api/space/%s/content" confluence-url space)
        ancestor-pred (ancestor-or-self= page)]
    (loop [start 0 ret (list)]
      (log/infof "Downloading %d pages starting from: %d" content-request-limit start)
      (let [query-params {:type "page" :start start :limit content-request-limit :expand content-request-expand}
            body (utils/get-json url {:accept :json :query-params query-params} config)
            next-path (get-in body [:page :_links :next])
            next-ret (lazy-cat ret (filter ancestor-pred (get-in body [:page :results] (list))))]
        (if-not next-path
          next-ret
          (let [next-start (+ start content-request-limit)]
            (recur next-start next-ret)))))))

(defn- write-pages [pages attachments {:keys [confluence-url output]}]
  (doseq [p pages]
    (let [{:keys [id title]} p
          content (-> (get-in p [:body :export_view :value])
                      ;; TODO: Too many options passed here and there. Extract them in a state?
                      (html/process id title attachments pages confluence-url))
          f (utils/filename output title)]
      (utils/write-file f content))))

(defn- write-toc [toc output]
  (utils/write-file (str output "/toc.xml") (h/html toc)))

(defn- ignored-media-type? [{:keys [mediaType]}]
  (#{"application/zip" "application/x-gzip"} mediaType))

(defn- download-attachments [pages {:keys [output confluence-url] :as config}]
  (let [attachment (fn [a] (utils/select-keys* a [[:title]
                                                  [:_links :download]
                                                  [:metadata :mediaType]
                                                  [:_expandable :container]]))
        attachments (->> pages
                         (mapcat #(get-in % [:children :attachment :results]))
                         (map attachment)
                         (remove ignored-media-type?)
                         (pmap (fn [{:keys [title download container]}]
                                 (let [page-id (last (string/split container #"/"))
                                       f (utils/attachment-filename output page-id title)
                                       url (str confluence-url download)]
                                   (when (utils/download-file url f config)
                                     (utils/attachment page-id title)))))
                         (into #{})
                         (doall))]
    (shutdown-agents)
    attachments))

(defn- log-retry
  "Prints a message to stdout that an error happened and going to be retried."
  [wrapped-ex attempt delay]
  (log/infof "%s, retrying in %.1f seconds... (%d)"
             (:e (ex-data wrapped-ex))
             (/ delay 1000.0)
             attempt))

(defn- export [{:keys [confluence-url space page output] :as config}]
  (if page
    (log/infof "Exporting Confluence page %s - %s from: %s" space page confluence-url)
    (log/infof "Exporting Confluence space %s from: %s" space confluence-url))

  (perseverance/retry {:strategy (perseverance/progressive-retry-strategy :max-count 3 :initial-delay 10000)
                       :selector ::utils/http-request
                       :log-fn log-retry}
    (let [pages (get-pages config)
          attachments (download-attachments pages config)]
      (write-pages pages attachments config)
      (write-toc (toc/html pages) output))))

(defn run [options]
  (-> (check-config options)
      utils/make-output-dirs
      export))
