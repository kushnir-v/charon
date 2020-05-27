(ns charon.steps-test
  (:require [charon.steps :as steps]
            [cheshire.core :as json]
            [clj-http.fake :refer :all]
            [clojure.test :refer :all]
            [lambdaisland.uri :as uri])
  (:import (clojure.lang ExceptionInfo)))

(def context {:confluence-url "http://confluence" :space "my-space"})

(deftest get-pages-test
  (testing "Empty space"
    (with-fake-routes-in-isolation
      {#"http://confluence/rest/api/space/my-space/content?.*"
       (fn [req]
         (let [query-params (uri/query-string->map (:query-string req))]
           (is (= {:type "page"
                   :start "0"
                   :limit "25"
                   :expand "body.export_view,children.page,children.attachment,history"} query-params)))
         {:status 200 :body (json/generate-string {})})}
      (let [res (steps/get-pages context)]
        (is (= [] res)))))

  (testing "Results fit in page"
    (with-fake-routes-in-isolation
      {#"http://confluence/rest/api/space/my-space/content?.*"
       (fn [_]
         {:status 200 :body (json/generate-string {:page {:results [{:id 1 :title "Welcome"}
                                                                    {:id 2 :title "Guide"}]}})})}
      (let [res (steps/get-pages context)]
        (is (= [{:id 1 :title "Welcome"}
                {:id 2 :title "Guide"}] res)))))

  (testing "Results exceed page"
    (with-redefs [steps/content-request-limit 2]
      (with-fake-routes-in-isolation
        {#"http://confluence/rest/api/space/my-space/content?.*"
         (fn [req]
           (let [{:keys [start]} (uri/query-string->map (:query-string req))
                 start->body {"0" {:page {:results [{:id 1 :title "Welcome"}
                                                    {:id 2 :title "Guide"}]
                                          :_links {:next "some1"}}}
                              "2" {:page {:results [{:id 3 :title "Page A"}
                                                    {:id 4 :title "Page B"}]
                                          :_links {:next "some2"}}}
                              "4" {:page {:results [{:id 5 :title "Page C"}]}}}]
             {:status 200 :body (json/generate-string (get start->body start))}))}
        (let [res (steps/get-pages context)]
          (is (= [{:id 1 :title "Welcome"}
                  {:id 2 :title "Guide"}
                  {:id 3 :title "Page A"}
                  {:id 4 :title "Page B"}
                  {:id 5 :title "Page C"}] res))))))

  (testing "Exception"
    (with-fake-routes-in-isolation
      {#"http://confluence/rest/api/space/my-space/content?.*"
       (fn [_]
         {:status 500 :body (json/generate-string {:error "Bad idea"})})}
      (is (thrown? ExceptionInfo (steps/get-pages context))))))
