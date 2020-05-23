(ns dev
  (:require [clojure.repl :refer :all]
            [clojure.stacktrace :refer [e
                                        print-cause-trace
                                        print-stack-trace
                                        print-throwable]]
            [clojure.tools.namespace.repl :refer [refresh
                                                  refresh-all
                                                  set-refresh-dirs]]
            [charon.core :as charon]
            [schema.core :as s]))

(alter-var-root #'*warn-on-reflection* (constantly true))


(set-refresh-dirs "src")
(s/set-fn-validation! true)

(def r refresh)

(comment
  (charon/run {:confluence-url "https://wiki.xandr.com"
               :space          "sdk"
               :output         "publish"
               :-overwrite     true})
  )
