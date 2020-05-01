(ns charon.core
  (:require [charon.export :as export]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log])
  (:gen-class))

(def run export/run)

(def cli-options
  [["-o" "--output OUTPUT" "Output directory"
    :missing "Output directory (--output) is required"]
   ["-s" "--space-url SPACE_URL" "Confluence space URL"
    :missing "Confluence space URL (--space-url) is required"]
   ["-u" "--user USER" "Username"]
   ["-p" "--password PASSWORD" "Password"]
   ["-h" "--help"]])

(defn- enrich-opts
  "Enrich options map with derived data.

  - Extract Confluence URL, `confluence-url`.
  - Extract Confluence space key, `space`.

  Return `nil` when data cannot be parsed.

  Example:
  ```
  (enrich-opts {:space-url \"https://wiki.xandr.com/display/sdk/Home\"})
  =>
  {:space-url \"https://wiki.xandr.com/display/sdk/Home\",
   :confluence-url \"https://wiki.xandr.com\",
   :space \"sdk\"}
  ```"
  [{:keys [space-url] :as m}]
  (if (string/includes? space-url "/display/")
    (let [[confluence-url path] (string/split "https://wiki.xandr.com/display/sdk/Home" #"/display/" 2)
          [space & _] (string/split path #"/" 2)]
      (assoc m :confluence-url confluence-url :space space))
    nil))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) {:exit-message summary :ok? true}
      errors {:exit-message (string/join \newline errors)}
      :else (if-let [enriched-options (enrich-opts options)]
              {:options enriched-options}
              {:exit-message "Unknown Space URL format"}))))

(defn exit [status msg]
  (log/error msg)
  (System/exit status))

(defn -main
  [& args]
  (log/infof "Running with arguments: %s" args)
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (try
        (time (run options))
        (log/info "Done")
        (catch Exception e
          (exit 2 (format "Failed: %s" (.getMessage e))))))))
