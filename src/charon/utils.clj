(ns charon.utils
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
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

(defprotocol Resource
  (file-name [_]))

(defrecord Attachment [page-id title]
  Resource
  (file-name [a]
    (format "%s-%s" (.page-id a) title)))

(defn attachment [page-id title]
  (->Attachment page-id (normalize-title title)))

(def attachments-dirname "files")

(defn attachment-filename
  ([^Attachment attachment]
   (str attachments-dirname "/" (.file-name attachment)))
  ([output ^Attachment attachment]
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

(defn- get-body [url r {:keys [username password]}]
  (let [opts (-> r
                 (merge {:socket-timeout 10000 :connection-timeout 10000})
                 (conj (when password [:basic-auth [username password]])))
        {:keys [status body] :as resp} (client/get url opts)]
    (if-not (= status 200)
      (throw+ {:type    ::request-failed
               :message "HTTP request to Confluence failed"
               :context resp})
      body)))

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

(defn write-file [f content]
  (log/infof "Writing: %s" f)
  (spit f content :encoding "UTF-8"))
