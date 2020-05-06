(defproject charon "0.1.2-SNAPSHOT"
  :description "Console utility for exporting spaces from Confluence"
  :url "https://github.com/shapiy/charon"
  :license {:name "GNU GENERAL PUBLIC LICENSE v3"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [aysylu/loom "1.0.2"]
                 [cheshire "5.10.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [clj-http "3.10.1"]
                 [com.grammarly/perseverance "0.1.3"]
                 [hiccup "1.0.5"]
                 [lambdaisland/uri "1.3.45"]
                 [org.clojure/tools.cli "1.0.194"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.jsoup/jsoup "1.13.1"]
                 [slingshot "0.12.2"]]
  :main charon.core
  :target-path "target/%s"
  :profiles {:debug {:debug true
                     :injections [(prn (into {} (System/getProperties)))]}
             :dev     {:source-paths ["dev"]
                       :plugins      [[camechis/deploy-uberjar "0.3.0"]
                                      [jonase/eastwood "0.3.10"]
                                      [lein-eftest "0.5.9"]]
                       :dependencies [[org.clojure/tools.namespace "1.0.0"]]}
             :uberjar {:aot :all}}
  :repositories [["github" {:url "https://maven.pkg.github.com/shapiy/charon"
                            :username "shapiy"
                            :password :env/github_token}]]
  :repl-options {:init-ns user})
