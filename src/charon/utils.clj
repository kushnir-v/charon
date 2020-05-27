(ns charon.utils
  (:require [charon.models :as models]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambdaisland.uri :as uri]
            [perseverance.core :refer [retriable]]
            [slingshot.slingshot :refer [throw+]])
  (:import (java.io File)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(defn url-decode [s] (URLDecoder/decode s (.name StandardCharsets/UTF_8)))

(defn- normalize-title [title]
  (-> title
      (url-decode)
      (string/lower-case)
      (string/replace #"[^\p{L}\d\.]" "-")))

(defn attachment [page-id title]
  (models/->Attachment page-id (normalize-title title)))

(def attachments-dirname "files")

(defn attachment-filename
  ([attachment]
   (str attachments-dirname "/" (.file-name attachment)))
  ([output attachment]
   (string/join "/" [output attachments-dirname (.file-name attachment)])))

(defn filename
  ([title]
   (format "%s.html" (normalize-title title)))
  ([output title]
   (str output "/" (filename title))))

(defn delete-dir [d]
  (log/infof "Deleting folder recursively: %s" d)
  (loop [fs [d]]
    (when-let [f (first fs)]
      (if-let [cs (seq (.listFiles (io/file f)))]
        (recur (concat cs fs))
        (do (io/delete-file f)
            (recur (rest fs)))))))

(defn id->title [pages]
  (into {} (map (juxt :id :title) pages)))

(defn title->page [pages]
  (into {} (map (juxt :title identity) pages)))

(defn make-dirs [^File f]
  (log/infof "Creating folder: %s" f)
  (.mkdirs f))

(defn make-output-dirs [{:keys [output -overwrite] :as config}]
  (let [output-dir ^File (io/file output)
        attachments-dir (io/file (str output "/" attachments-dirname))]
    (cond
      (.isFile output-dir)
      (throw+ {:type    ::invalid-opts
               :message "Output is file"
               :context output})

      (and (.isDirectory output-dir) (not -overwrite))
      (throw+ {:type    ::invalid-opts
               :message "Output folder exists"
               :context output})

      (.isDirectory output-dir)
      ;; Creating attachments dir will also create the output dir.
      ((juxt delete-dir make-dirs) attachments-dir)

      :else
      (make-dirs attachments-dir)))
  config)

(defn- debug-get-body [url opts]
  (let [url-str (-> (uri/uri url)
                    (assoc :query (uri/map->query-string (:query-params opts)))
                    str)
        basic-auth (:basic-auth opts)
        command (concat ["curl" "-XGET"]
                        (when basic-auth
                          ["-u" (string/join ":" basic-auth)])
                        [(str "'" url-str "'")])]
    (log/debug (string/join " " command))))

(defn- get-body [url r {:keys [username password]}]
  (let [opts (-> r
                 (merge {:socket-timeout 10000 :connection-timeout 10000 :cookie-policy :none})
                 (conj (when password [:basic-auth [username password]])))
        _ (debug-get-body url opts)
        {:keys [body]} (client/get url opts)]
    body))

(defn get-json [url r config]
  (retriable {:catch [Exception]
              :tag   ::http-request}
    (json/parse-string (get-body url r config) true)))

(defn download-file [url f config]
  (try
    (log/infof "Writing: %s" f)
    (retriable {:catch [Exception]
                :tag   ::download-file}
      (clojure.java.io/copy
        (get-body url {:as :stream} config)
        (io/file f)))
    (catch Exception e
      (log/warnf e "Cannot download file: %s" url))))

;; Thanks Anders, https://andersmurphy.com/2019/11/30/clojure-flattening-key-paths.html
(defn flatten-paths
  ([m separator]
   (flatten-paths m separator []))
  ([m separator path]
   (->> (map (fn [[k v]]
               (if (and (map? v) (not-empty v))
                 (flatten-paths v separator (conj path k))
                 [(->> (conj path k)
                       (map name)
                       (clojure.string/join separator)
                       keyword) v]))
             m)
        (into {}))))

(defn write-file [f content]
  (log/infof "Writing: %s" f)
  (spit f content :encoding "UTF-8"))
