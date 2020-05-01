(ns charon.utils
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [loom.alg :refer [pre-span]]
            [slingshot.slingshot :refer [throw+]]
            [clojure.tools.logging :as log])
  (:import (java.io File)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(defn- normalize-title [title]
  (-> title
      (URLDecoder/decode (.name StandardCharsets/UTF_8))
      (string/lower-case)
      (string/replace #"[^\p{L}\d]" "-")))

(defprotocol Named
  (name [_]))

(defrecord Attachment [page-id title]
  Named
  (name [a]
    (format "%s-%s" (.page-id a) title)))

(defn attachment [page-id title]
  (->Attachment page-id (normalize-title title)))

(def attachments-dirname "files")

(defn attachment-filename
  ([^Attachment attachment]
   (str attachments-dirname "/" (.name attachment)))
  ([output page-id title]
   (string/join "/" [output
                     attachments-dirname
                     (.name (attachment page-id title))])))

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

(defn download-file [url f]
  (log/infof "Writing: %s" f)
  (clojure.java.io/copy
    (:body (client/get url {:as :stream}))
    (io/file f)))

(defn write-file [f content]
  (log/infof "Writing: %s" f)
  (spit f content :encoding "UTF-8"))

(defn select-keys* [m paths]
  (into {} (map (fn [p]
                  [(last p) (get-in m p)]))
        paths))
