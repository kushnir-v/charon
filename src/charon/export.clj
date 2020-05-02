(ns charon.export
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.html :as html]
            [charon.toc :as toc]
            [charon.utils :as utils]
            [hiccup.core :as h]
            [slingshot.slingshot :refer [throw+]]))

;; TODO: Retries
;; TODO: Attachments
;; TODO: Memory footprint?
;; TODO: Authorization

(defn- check-config [{:keys [confluence-url output space] :as m}]
  (if (some nil? [confluence-url output space])
    (throw+ {:type    ::invalid-opts
             :message "Some required options are nil"
             :context m})
    m))

(def content-request-limit 25)
(def content-request-expand (string/join "," ["body.export_view" "children.page" "children.attachment"]))

(defn- fetch-pages [{:keys [confluence-url space]}]
  (let [content-url (format "%s/rest/api/space/%s/content" confluence-url space)]
    (loop [start 0 ret (list)]
      (log/infof "Downloading %d pages starting from: %d" content-request-limit start)
      (let [query-params {:type "page" :start start :limit content-request-limit :expand content-request-expand}
            {:keys [status body] :as resp} (client/get content-url {:accept :json :query-params query-params})]
        (if-not (= status 200)
          (throw+ {:type    ::request-failed
                   :message "HTTP request to Confluence failed"
                   :context resp})
          (let [body-map (json/parse-string body true)
                next-path (get-in body-map [:page :_links :next])
                next-ret (lazy-cat ret (get-in body-map [:page :results] (list)))]
            (if-not next-path
              next-ret
              (let [next-start (+ start content-request-limit)]
                (recur next-start next-ret)))))))))

(defn- write-pages [pages output attachments]
  (doseq [p pages]
    (let [{:keys [id title]} p
          content (-> (get-in p [:body :export_view :value])
                      (html/process id title attachments))
          f (utils/filename output title)]
      (utils/write-file f content))))

(defn- write-toc [toc output]
  (utils/write-file (str output "/toc.xml") (h/html toc)))

(defn- ignored-media-type? [{:keys [mediaType]}]
  (#{"application/zip" "application/x-gzip"} mediaType))

(defn- download-attachments [pages output confluence-url]
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
                                   (utils/download-file url f)
                                   (utils/attachment page-id title))))
                         (into #{})
                         (doall))]
    (shutdown-agents)
    attachments))

(defn- export [{:keys [confluence-url space output] :as config}]
  (log/infof "Exporting Confluence space \"%s\" from: %s" space confluence-url)
  (let [pages (fetch-pages config)
        attachments (download-attachments pages output confluence-url)]
    (write-pages pages output attachments)
    (write-toc (toc/html pages) output)))

(defn run [options]
  (-> (check-config options)
      utils/make-output-dirs
      export))
