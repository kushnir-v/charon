(ns charon.export
  (:require [clojure.tools.logging :as log]
            [charon.models :as models]
            [charon.steps :as steps]
            [charon.utils :as utils]
            [perseverance.core :refer [retry progressive-retry-strategy]]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]])
  (:import (clojure.lang ExceptionInfo)))

;; TODO: Memory footprint?
;; TODO: REPL workflow

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

(defn- export [{:keys [confluence-url space page output] :as ctx}]
  (if page
    (log/infof "Exporting Confluence page %s - %s from: %s" space page confluence-url)
    (log/infof "Exporting Confluence space %s from: %s" space confluence-url))

  (retry {:strategy (progressive-retry-strategy :max-count 3 :initial-delay 10000)
          :selector (tags-in? #{::utils/http-request ::utils/download-file})
          :log-fn   log-retry}
    ;; TODO: Document the design decision behind steps/run and continuously enriching context.
    ;; When extending the flow, models/Context should be updated, too.
    (steps/run ctx
               [:space-pages steps/get-pages]
               [:attachment->url steps/attachment->url]
               [:space-attachments steps/attachments]
               [:pages steps/pages]
               [:attachments steps/write-pages]
               steps/write-attachments
               steps/write-toc)))

(defn run [options]
  (-> (s/validate models/Config options)
      utils/make-output-dirs
      export))
