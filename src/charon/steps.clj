(ns charon.steps
  "Export steps."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [charon.structure :as structure]
            [charon.html :as html]
            [charon.models :as models]
            [charon.utils :as utils]
            [hiccup.core :as h]
            [schema.core :as s]))

(def content-request-limit 25)
(def content-request-expand (string/join "," ["body.export_view" "children.page" "children.attachment" "history"]))

(defn get-pages
  "Fetch all space content using pagination."
  [{:keys [confluence-url space] :as ctx}]
  (let [url (format "%s/rest/api/space/%s/content" confluence-url space)]
    (loop [start 0 ret (list)]
      (log/infof "Downloading %d pages starting from: %d" content-request-limit start)
      (let [query-params {:type "page" :start start :limit content-request-limit :expand content-request-expand}
            body (utils/get-json url {:accept :json :query-params query-params} ctx)
            next-path (get-in body [:page :_links :next])
            next-ret (lazy-cat ret (get-in body [:page :results] (list)))]
        (if-not next-path
          next-ret
          (let [next-start (+ start content-request-limit)]
            (recur next-start next-ret)))))))

(defn attachment->url
  "Returns a set of all attachments in the confluence space."
  [{:keys [confluence-url space-pages]}]
  (let [attachment-url (fn [a] (let [title (:title a)
                                     download (get-in a [:_links :download])
                                     page-id (-> (get-in a [:_expandable :container])
                                                 (string/split #"/")
                                                 last)
                                     url (str confluence-url download)]
                                 [(utils/attachment page-id title) url]))]
    (->> space-pages
         (mapcat #(get-in % [:children :attachment :results]))
         (map attachment-url)
         (into {}))))

(def attachments (comp set keys :attachment->url))

(defn pages
  "Return pages to be downloaded: all pages in the space for `--space-url`, or a particular subtree
  for `--page-url`."
  [{:keys [page space-pages]}]
  (if page (structure/subtree-pages space-pages page) space-pages))

(defn write-pages
  "Process pages, write them to disc and return a set of referenced attachments."
  [{:keys [output pages] :as ctx}]
  (reduce
    (fn [res p]
      (let [{:keys [content attachments]} (html/process p ctx)
            {:keys [title]} p
            f (utils/filename output title)]
        (utils/write-file f content)
        (set/union res attachments)))
    #{}
    pages))

(defn write-attachments
  "Write attachments.

  If `--page-url` is provided, download only referenced attachments."
  [{:keys [attachment->url attachments output page space-attachments] :as ctx}]
  (let [_ (->> (if page attachments space-attachments)
               (pmap (fn [attachment]
                       (let [f (utils/attachment-filename output attachment)
                             url (get attachment->url attachment)]
                         (utils/download-file url f ctx))))
               (doall))]
    (shutdown-agents)))

(defn write-toc
  "Write Table of Contents."
  [{:keys [output pages]}]
  (let [toc (structure/toc-html pages)
        f (str output "/toc.xml")]
    (utils/write-file f (h/html toc))))

(defn run
  "Run uniform export steps and associate results in the context for the further usage."
  [ctx & args]
  (s/validate models/Context ctx)
  (let [[arg & more] args]
    (if-not arg
      ctx
      ;; Vectors signify that function application result has to be associated in the context: [:k f].
      ;; If input is just a function, the context is not enriched.
      (let [[k f] (if-not (vector? arg) [nil arg] arg)
            res (f ctx)
            next-ctx (if k (assoc ctx k res) ctx)]
        (recur next-ctx more)))))
