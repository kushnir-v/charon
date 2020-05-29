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
           (is (= {:type   "page"
                   :start  "0"
                   :limit  "25"
                   :expand "body.export_view,children.page,children.attachment,history,history.lastUpdated"} query-params)))
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

  (testing "Results on several pages"
    (with-redefs [steps/content-request-limit 2]
      (with-fake-routes-in-isolation
        {#"http://confluence/rest/api/space/my-space/content?.*"
         (fn [req]
           (let [{:keys [start]} (uri/query-string->map (:query-string req))
                 start->body {"0" {:page {:results [{:id 1 :title "Welcome"}
                                                    {:id 2 :title "Guide"}]
                                          :_links  {:next "some1"}}}
                              "2" {:page {:results [{:id 3 :title "Page A"}
                                                    {:id 4 :title "Page B"}]
                                          :_links  {:next "some2"}}}
                              "4" {:page {:results [{:id 5 :title "Page C"}]}}}]
             {:status 200 :body (json/generate-string (get start->body start))}))}
        (let [res (steps/get-pages context)]
          (is (= [{:id 1 :title "Welcome"}
                  {:id 2 :title "Guide"}
                  {:id 3 :title "Page A"}
                  {:id 4 :title "Page B"}
                  {:id 5 :title "Page C"}] res))))))

  (testing "Child pages fit in page"
    (let [page {:id       1
                :title    "Welcome"
                :children {:page {:results [{:id 2 :title "Child"}]
                                  :limit   25
                                  :size    1}}}]
      (with-fake-routes-in-isolation
        {#"http://confluence/rest/api/space/my-space/content?.*"
         (fn [_]
           {:status 200
            :body   (json/generate-string {:page
                                           {:results [page]}})})}
        (let [res (steps/get-pages context)]
          (is (= [page] res))))))

  (testing "Child pages on several pages"
    (with-redefs [steps/content-request-limit 2]
      (with-fake-routes-in-isolation
        {#"http://confluence/rest/api/space/my-space/content?.*"
         (let [page {:page
                     {:results [{:id       1
                                 :title    "Welcome"
                                 :children {:page {:results [{:id 2} {:id 3}]
                                                   :limit   2
                                                   :size    2}}}]}}]
           (fn [_]
             {:status 200
              :body   (json/generate-string page)}))
         #"http://confluence/rest/api/content/1?.*"
         (fn [req]
           (let [{:keys [start] :as query-params} (uri/query-string->map (:query-string req))
                 start->body {"0" {:page {:results [{:id 2 :title "Child A"}
                                                    {:id 3 :title "Child B"}]
                                          :_links  {:next "some1"}}}
                              "2" {:page {:results [{:id 4 :title "Child C"}
                                                    {:id 5 :title "Child D"}]
                                          :_links  {:next "some2"}}}
                              "4" {:page {:results [{:id 6 :title "Child E"}]}}}]
             (is (= {:limit "2" :expand "page"} (dissoc query-params :start)))
             {:status 200 :body (json/generate-string (get start->body start))}))}
        (let [res (steps/get-pages context)]
          (is (= [{:id       1
                   :title    "Welcome"
                   :children {:page {:results [{:id 2 :title "Child A"}
                                               {:id 3 :title "Child B"}
                                               {:id 4 :title "Child C"}
                                               {:id 5 :title "Child D"}
                                               {:id 6 :title "Child E"}]}}}] res))))))

  (testing "Child attachments fit in page"
    (let [page {:id       1
                :title    "Welcome"
                :children {:attachment {:results [{:id 2 :title "Image.jpg"}]
                                        :limit   25
                                        :size    1}}}]
      (with-fake-routes-in-isolation
        {#"http://confluence/rest/api/space/my-space/content?.*"
         (fn [_]
           {:status 200
            :body   (json/generate-string {:page
                                           {:results [page]}})})}
        (let [res (steps/get-pages context)]
          (is (= [page] res))))))

  (testing "Child attachments on several pages"
    (with-redefs [steps/content-request-limit 2]
      (with-fake-routes-in-isolation
        {#"http://confluence/rest/api/space/my-space/content?.*"
         (let [page {:page
                     {:results [{:id       1
                                 :title    "Welcome"
                                 :children {:attachment {:results [{:id 2} {:id 3}]
                                                         :limit   2
                                                         :size    2}}}]}}]
           (fn [_]
             {:status 200
              :body   (json/generate-string page)}))
         #"http://confluence/rest/api/content/1?.*"
         (fn [req]
           (let [{:keys [start] :as query-params} (uri/query-string->map (:query-string req))
                 start->body {"0" {:attachment {:results [{:id 2 :title "Image A.jpg"}
                                                          {:id 3 :title "Image B.jpg"}]
                                                :_links  {:next "some1"}}}
                              "2" {:attachment {:results [{:id 4 :title "Image C.jpg"}
                                                          {:id 5 :title "Image D.jpg"}]
                                                :_links  {:next "some2"}}}
                              "4" {:attachment {:results [{:id 6 :title "Image E.jpg"}]}}}]
             (is (= {:limit "2" :expand "attachment"} (dissoc query-params :start)))
             {:status 200 :body (json/generate-string (get start->body start))}))}
        (let [res (steps/get-pages context)]
          (is (= [{:id       1
                   :title    "Welcome"
                   :children {:attachment {:results [{:id 2 :title "Image A.jpg"}
                                                     {:id 3 :title "Image B.jpg"}
                                                     {:id 4 :title "Image C.jpg"}
                                                     {:id 5 :title "Image D.jpg"}
                                                     {:id 6 :title "Image E.jpg"}]}}}] res))))))

  (testing "Exception"
    (with-fake-routes-in-isolation
      {#"http://confluence/rest/api/space/my-space/content?.*"
       (fn [_]
         {:status 500 :body (json/generate-string {:error "Bad idea"})})}
      (is (thrown? ExceptionInfo (steps/get-pages context))))))
