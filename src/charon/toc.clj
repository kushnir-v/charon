;; TODO: Get rid of loom.
(ns charon.toc
  (:require [clojure.set :as set]
            [charon.utils :as utils]
            [loom.graph :as g]
            [clojure.walk :as walk]))

(defn- data [pages]
  (loop [graph (g/digraph) child-ids (transient #{}) rest-pages pages]
    (if-let [p (first rest-pages)]
      (let [id (:id p)
            page-child-ids (->> (get-in p [:children :page :results])
                                (map :id)
                                (set))]
        (recur (if (seq page-child-ids)
                 (apply g/add-edges graph (map #(vector id %) page-child-ids))
                 (g/add-nodes graph id))
               (reduce conj! child-ids page-child-ids)
               (rest rest-pages)))

      (let [roots (set/difference (:nodeset graph) (persistent! child-ids))]
        {:graph graph :roots roots}))))

(defn- branch [id graph id->position]
  (let [children (get (:adj graph) id)
        ret [id]]
    (if (seq children)
      (->> children
           (sort-by id->position)
           (mapv #(branch % graph id->position))
           (into ret))
      ret)))

(defn- page-position [page]
  (let [position (get-in page [:extensions :position] ##Inf)]
    ;; Can be, for example, "none".
    (if (number? position) position ##Inf)))

(defn- tree [pages {:keys [graph roots]}]
  (let [id->position (->> pages
                          (map (juxt :id page-position))
                          (into {}))]
    (->> roots
         (sort-by id->position)
         (mapv #(branch % graph id->position)))))

(defn- nav [pages]
  (let [id->title (into {} (map (juxt :id :title) pages))]
    (fn [form]
      (if (and (vector? form) (string? (first form)))
        (let [id (first form)
              title (get id->title id)
              filename (utils/filename title)]
          (into [:nav {:title title :href filename}] (rest form)))
        form))))

(defn html [pages]
  (->> (data pages)
       (tree pages)
       (walk/postwalk (nav pages))
       (into [:toc])))