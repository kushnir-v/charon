(ns charon.structure
  "Generation of Table of Contents and hierarchical filtering of pages."
  (:require [clojure.set :as set]
            [charon.utils :as utils]
            [clojure.walk :as walk]))

(defn- data [pages]
  (reduce
    (fn [{:keys [ids child-ids id->child-ids]} page]
      (let [page-id (:id page)
            page-child-ids (->> (get-in page [:children :page :results])
                                (map :id)
                                set)]
        {:ids           (conj ids page-id)
         :child-ids     (set/union child-ids page-child-ids)
         :id->child-ids (conj id->child-ids [page-id page-child-ids])}))
    {:ids #{} :child-ids #{} :id->child-ids {}}
    pages))

(defn- branch [id id->child-ids id->sort-key]
  (let [child-ids (get id->child-ids id)
        ret [id]]
    (if (seq child-ids)
      (->> child-ids
           (sort-by id->sort-key)
           (mapv #(branch % id->child-ids id->sort-key))
           (into ret))
      ret)))

(defn- page-position-with-title [page]
  (let [position (get-in page [:extensions :position])]
    ;; Can be, for example, "none".
    [(if (number? position) position ##Inf) (:title page)]))

(defn- tree [pages {:keys [ids child-ids id->child-ids]}]
  (let [root-ids (set/difference ids child-ids)
        id->sort-key (->> pages
                          (map (juxt :id page-position-with-title))
                          (into {}))]
    (->> root-ids
         (sort-by id->sort-key)
         (mapv #(branch % id->child-ids id->sort-key)))))

(defn- nav [pages]
  (let [id->title (utils/id->title pages)]
    (fn [form]
      (if (and (vector? form) (string? (first form)))
        (let [id (first form)
              title (get id->title id)
              filename (utils/filename title)]
          (into [:nav {:title title :href filename}] (rest form)))
        form))))

(defn toc-html
  "Generates hiccup structure for Table of Contents HTML."
  [pages]
  (->> (data pages)
       (tree pages)
       (walk/postwalk (nav pages))
       (into [:toc])))

(defn subtree-pages
  "Returns the `--page-url` and its descendants."
  [space-pages page-title]
  (let [title->page (utils/title->page space-pages)
        child-pages (fn [p] (->> (get-in p [:children :page :results])
                                 (map (comp title->page :title))))
        root (get title->page page-title)]
    (loop [res '() pages (list root)]
      (let [next-res (concat res pages)
            children (mapcat child-pages pages)]
        (if (seq children)
          (recur next-res children)
          next-res)))))
