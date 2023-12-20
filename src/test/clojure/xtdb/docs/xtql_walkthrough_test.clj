(ns xtdb.docs.xtql-walkthrough-test
  (:require [jsonista.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.test :as t :refer [deftest testing]]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]
            [xtdb.xtql.edn :as x-edn]
            [xtdb.xtql.json :as x-json]
            [xtdb.error :as err])
  (:import (com.fasterxml.jackson.core JsonParser$Feature)))

(t/use-fixtures :each tu/with-node)

(def comment-object-mapper
  (-> (json/object-mapper)
      (.configure JsonParser$Feature/ALLOW_COMMENTS true)))

(def examples
  (-> "./src/test/resources/docs/xtql_tutorial_examples.yaml"
      slurp
      (yaml/parse-string :keywords false)))

(defn json-example [name]
  (-> (get examples name)
      (json/read-value comment-object-mapper)
      x-json/parse-query
      x-edn/unparse))

(defn sql-example [name]
  (get examples name))

(def users
  [(xt/put :users {:xt/id "ivan", :first-name "Ivan", :last-name "Ivanov", :age 25})
   (-> (xt/put :users {:xt/id "petr", :first-name "Petr", :last-name "Petrov", :age 25})
       (xt/starting-from #inst "2018"))])

(def old-users
  [(-> (xt/put :old-users {:xt/id "ivan", :given-name "Ivan", :surname "Ivanov"})
       (xt/starting-from #inst "2017"))
   (-> (xt/put :old-users {:xt/id "petr", :given-name "Petr", :surname "Petrov"})
       (xt/starting-from #inst "2018"))])

(def articles
  [(xt/put :articles {:xt/id 1, :author-id "ivan", :title "First" :content "My first blog"})
   (xt/put :articles {:xt/id 2, :author-id "ivan", :title "Second" :content "My second blog"})])

(def posts
  [(xt/put :posts {:xt/id 1, :author-id "ivan", :title "First" :content "My first blog"})
   (xt/put :posts {:xt/id 2, :author-id "ivan", :title "Second" :content "My second blog"})])

(def comments
  [(xt/put :comments {:xt/id 1, :article-id 1, :post-id 1, :created-at #inst "2020", :comment "1"})
   (xt/put :comments {:xt/id 2, :article-id 1, :post-id 1, :created-at #inst "2021", :comment "2"})
   (xt/put :comments {:xt/id 3, :article-id 2, :post-id 2, :created-at #inst "2022", :comment "1"})])

(def customers
  [(xt/put :customers {:xt/id "ivan"})
   (xt/put :customers {:xt/id "petr"})])

(def orders
  [(xt/put :orders {:xt/id 1, :customer-id "ivan", :currency :gbp, :order-value 100})
   (xt/put :orders {:xt/id 2, :customer-id "ivan", :currency :gbp, :order-value 150})])

(def promotions
  [(xt/put :promotions {:xt/id 1, :promotion-type "christmas"})
   (xt/put :promotions {:xt/id 2, :promotion-type "general"})])

(deftest basic-operations

  (xt/submit-tx tu/*node* users)

  (t/is (= #{{:user-id "ivan", :first-name "Ivan", :last-name "Ivanov"}
             {:user-id "petr", :first-name "Petr", :last-name "Petrov"}}
           (set
             (xt/q tu/*node*
                   ';; tag::bo-xtql-1[]
                   (from :users [{:xt/id user-id} first-name last-name])
                   ;; end::bo-xtql-1[]
                   ,))
           (set (xt/q tu/*node* (json-example "bo-json-1")))
           (set (xt/q tu/*node* (sql-example "bo-sql-1")
                      {:key-fn :clojure}))))

  (t/is (= [{:first-name "Ivan", :last-name "Ivanov"}]
           (xt/q tu/*node*
                 '
                 ;; tag::bo-xtql-2[]
                 (from :users [{:xt/id "ivan"} first-name last-name])
                 ;; end::bo-xtql-2[]
                 ,)
           (xt/q tu/*node* (json-example "bo-json-2"))
           (xt/q tu/*node* (sql-example "bo-sql-2")
                 {:key-fn :clojure})))

  (t/is (= [{:user-id "ivan", :first-name "Ivan", :last-name "Ivanov"}
            {:user-id "petr", :first-name "Petr", :last-name "Petrov"}]
           (xt/q tu/*node*
                 '
                 ;; tag::bo-xtql-3[]
                 (-> (from :users [{:xt/id user-id} first-name last-name])
                     (order-by last-name first-name)
                     (limit 10))
                 ;; end::bo-xtql-3[]
                 ,)
           (xt/q tu/*node* (json-example "bo-json-3"))
           (xt/q tu/*node* (sql-example "bo-sql-3")
                 {:key-fn :clojure}))))

(deftest joins

  (xt/submit-tx tu/*node*
                (concat users articles
                        customers orders))

  (t/is (= #{{:user-id "ivan", :first-name "Ivan", :last-name "Ivanov", :content "My first blog", :title "First"}
             {:user-id "ivan", :first-name "Ivan", :last-name "Ivanov", :content "My second blog", :title "Second"}}
           (set
             (xt/q tu/*node*
                   '
                   ;; tag::joins-xtql-1[]
                   (unify (from :users [{:xt/id user-id} first-name last-name])
                          (from :articles [{:author-id user-id} title content]))
                   ;; end::joins-xtql-1[]
                   ,))
           (set (xt/q tu/*node* (json-example "joins-json-1")))
           (set (xt/q tu/*node* (sql-example "joins-sql-1")
                      {:key-fn :clojure}))))

  (t/is (= #{{:age 25, :uid1 "ivan", :uid2 "petr"}
             {:age 25, :uid1 "petr", :uid2 "ivan"}}
           (set
             (xt/q tu/*node*
                   '
                   ;; tag::joins-xtql-2[]
                   (unify (from :users [{:xt/id uid1} age])
                          (from :users [{:xt/id uid2} age])
                          (where (<> uid1 uid2)))
                   ;; end::joins-xtql-2[]
                   ,))
           (set (xt/q tu/*node* (json-example "joins-json-2")))
           (set (xt/q tu/*node* (sql-example "joins-sql-2")
                      {:key-fn :clojure}))))

  (t/is (= #{{:cid "ivan", :order-value 150, :currency :gbp}
             {:cid "ivan", :order-value 100, :currency :gbp}
             {:cid "petr", :order-value nil, :currency nil}}
           (set
             (xt/q tu/*node*
                   '
                   ;; tag::joins-xtql-3[]
                   (-> (unify (from :customers [{:xt/id cid}])
                              (left-join (from :orders [{:xt/id oid, :customer-id cid} currency order-value])
                                         [cid currency order-value]))
                       (limit 100))
                   ;; end::joins-xtql-3[]
                   ,))
           (set (xt/q tu/*node* (json-example "joins-json-3")))
           (set (xt/q tu/*node* (sql-example "joins-sql-3")
                      {:key-fn :clojure}))))

  (t/is (= [{:cid "petr"}]
           (xt/q tu/*node*
                 '
                 ;; tag::joins-xtql-4[]
                 (-> (unify (from :customers [{:xt/id cid}])
                            (where (not (exists? (from :orders [{:customer-id $cid}])
                                                 {:args [cid]}))))
                     (limit 100))
                 ;; end::joins-xtql-4[]
                 ,)
           (xt/q tu/*node* (json-example "joins-json-4"))
           (xt/q tu/*node* (sql-example "joins-sql-4")
                 {:key-fn :clojure}))))

(deftest projections

  (xt/submit-tx tu/*node* (concat users articles))

  (t/is (= #{{:first-name "Ivan", :last-name "Ivanov", :full-name "Ivan Ivanov"}
             {:first-name "Petr", :last-name "Petrov", :full-name "Petr Petrov"}}
           (set
             (xt/q tu/*node*
                   '
                   ;; tag::proj-xtql-1[]
                   (-> (from :users [first-name last-name])
                       (with {:full-name (concat first-name " " last-name)}))
                   ;; end::proj-xtql-1[]
                   ,))
           (set (xt/q tu/*node* (json-example "proj-json-1")))
           (set (xt/q tu/*node* (sql-example "proj-sql-1")
                      {:key-fn :clojure}))))

  (t/is (= #{{:full-name "Ivan Ivanov", :title "First", :content "My first blog"}
             {:full-name "Ivan Ivanov", :title "Second", :content "My second blog"}}
           (set
             (xt/q tu/*node*
                   '
                   ;; tag::proj-xtql-2[]
                   (-> (unify (from :users [{:xt/id user-id} first-name last-name])
                              (from :articles [{:author-id user-id} title content]))
                       (return {:full-name (concat first-name " " last-name)} title content))
                   ;; end::proj-xtql-2[]
                   ,))
           (set (xt/q tu/*node* (json-example "proj-json-2")))
           (set (xt/q tu/*node* (sql-example "proj-sql-2")
                      {:key-fn :clojure}))))

  (t/is (= #{{:first-name "Ivan", :last-name "Ivanov", :title "Second", :content "My second blog"}
             {:first-name "Ivan", :last-name "Ivanov", :title "First", :content "My first blog"}}
           (set
             (xt/q tu/*node*
                   '
                   ;; tag::proj-xtql-3[]
                   (-> (unify (from :users [{:xt/id user-id} first-name last-name])
                              (from :articles [{:author-id user-id} title content]))
                       (without :user-id))
                   ;; end::proj-xtql-3[]
                   ,))
           (set (xt/q tu/*node* (json-example "proj-json-3")))
           (set (xt/q tu/*node* (sql-example "proj-sql-3")
                      {:key-fn :clojure})))))

(deftest aggregations

  (xt/submit-tx tu/*node* (concat customers orders))

  (testing "To count/sum/average values, we use `aggregate`:"

    (t/is (= #{{:cid "ivan", :currency :gbp, :order-count 2, :total-value 250}
               {:cid "petr", :currency nil, :order-count 0, :total-value 0}}
             (set
               (xt/q tu/*node*
                 '
                 ;; tag::aggr-xtql-1[]
                 (-> (unify (from :customers [{:xt/id cid}])
                            (left-join (from :orders [{:xt/id oid :customer-id cid} currency order-value])
                                       [oid cid currency order-value]))
                     (aggregate cid currency
                                {:order-count (count oid)
                                 :total-value (sum order-value)})
                     (with {:total-value (coalesce total-value 0)})
                     (order-by {:val total-value :dir :desc})
                     (limit 100))
                 ;; end::aggr-xtql-1[]
                 ,))
             (set (xt/q tu/*node* (json-example "aggr-json-1")))
             (set (xt/q tu/*node* (sql-example "aggr-sql-1")
                        {:key-fn :clojure}))))))


(deftest pull

  (xt/submit-tx tu/*node*
    (concat
      articles comments
      [(xt/put :authors {:xt/id "ivan", :first-name "Ivan", :last-name "Ivanov"})
       (xt/put :authors {:xt/id "petr", :first-name "Petr", :last-name "Petrov"})]))

  (testing "For example, if a user is reading an article, we might also want to show them details about the author as well as any comments."

    (t/is (= #{{:article-id 1
                :title "First",
                :content "My first blog",
                :author-id "ivan",
                :author {:last-name "Ivanov", :first-name "Ivan"},
                :comments [{:created-at #time/zoned-date-time "2021-01-01T00:00Z[UTC]", :comment "2"}
                           {:created-at #time/zoned-date-time "2020-01-01T00:00Z[UTC]", :comment "1"}],}
               {:article-id 2
                :title "Second",
                :content "My second blog",
                :author-id "ivan",
                :author {:last-name "Ivanov", :first-name "Ivan"},
                :comments [{:created-at #time/zoned-date-time "2022-01-01T00:00Z[UTC]", :comment "1"}],}}
             (set
               (xt/q tu/*node*
                 '
                 ;; tag::pull-xtql-1[]
                 (-> (from :articles [{:xt/id article-id} title content author-id])

                     (with {:author (pull (from :authors [{:xt/id $author-id} first-name last-name])
                                          {:args [author-id]})


                            :comments (pull* (-> (from :comments [{:article-id $article-id} created-at comment])
                                                 (order-by {:val created-at :dir :desc})
                                                 (limit 10))
                                             {:args [article-id]})}))
                 ;; end::pull-xtql-1[]
                 ,))
             #_ ;; TODO: Waiting for `pull` to be implemented:
             (set (xt/q tu/*node* (json-example "pull-json-1")))))))
            ;; No SQL for this one

(deftest bitemporality

  (xt/submit-tx tu/*node* users)

  (t/is (= [{:first-name "Petr", :last-name "Petrov"}]
           (xt/q tu/*node*
             '
             ;; tag::bitemp-xtql-1[]
             (from :users {:for-valid-time (at #inst "2020-01-01")
                           :bind [first-name last-name]})
             ;; end::bitemp-xtql-1[]
             ,)
           (xt/q tu/*node* (json-example "bitemp-json-1"))
           (xt/q tu/*node* (sql-example "bitemp-sql-1")
                 {:key-fn :clojure})))

  (t/is (= #{{:first-name "Ivan", :last-name "Ivanov"}
             {:first-name "Petr", :last-name "Petrov"}}
           (set
             (xt/q tu/*node*
               '
               ;; tag::bitemp-xtql-2[]
               (from :users {:for-valid-time :all-time
                             :bind [first-name last-name]})
               ;; end::bitemp-xtql-2[]
               ,))
           (set (xt/q tu/*node* (json-example "bitemp-json-2")))
           (set (xt/q tu/*node* (sql-example "bitemp-sql-2")
                      {:key-fn :clojure}))))

  (t/is (= [{:user-id "petr"}]
           (xt/q tu/*node*
                 '
                 ;; tag::bitemp-xtql-3[]
                 (unify (from :users {:for-valid-time (at #inst "2018")
                                      :bind [{:xt/id user-id}]})

                        (from :users {:for-valid-time (at #inst "2023")
                                      :bind [{:xt/id user-id}]}))
                 ;; end::bitemp-xtql-3[]
                 ,)
           (xt/q tu/*node* (json-example "bitemp-json-3")))))


(deftest DML-Insert-xtql
  (xt/submit-tx tu/*node* old-users)

  (t/is (= []
           (xt/q tu/*node*
                 '(from :users [first-name last-name]))))

  (let [node tu/*node*]
    ;; tag::DML-Insert-xtql[]
    (xt/submit-tx node
      [(xt/insert-into :users
         '(from :old-users [xt/id {:given-name first-name, :surname last-name}
                            xt/valid-from xt/valid-to]))])
    ;; end::DML-Insert-xtql[]
    ,)

  (t/is (= #{{:first-name "Ivan"
              :last-name "Ivanov"
              :xt/valid-from #time/zoned-date-time "2017-01-01T00:00Z[UTC]"
              :xt/valid-to nil}
             {:first-name "Petr"
              :last-name "Petrov"
              :xt/valid-from #time/zoned-date-time "2018-01-01T00:00Z[UTC]"
              :xt/valid-to nil}}
           (set
             (xt/q tu/*node*
                   '(from :users [first-name last-name
                                  xt/valid-from xt/valid-to]))))))

(deftest DML-Insert-sql
  (xt/submit-tx tu/*node* old-users)

  (t/is (= []
           (xt/q tu/*node*
                 '(from :users [first-name last-name]))))

  (xt/submit-tx tu/*node*
    [(xt/sql-op (sql-example "DML-Insert-sql"))])

  (t/is (= #{{:first-name "Ivan"
              :last-name "Ivanov"
              :xt/valid-from #time/zoned-date-time "2017-01-01T00:00Z[UTC]"
              :xt/valid-to nil}
             {:first-name "Petr"
              :last-name "Petrov"
              :xt/valid-from #time/zoned-date-time "2018-01-01T00:00Z[UTC]"
              :xt/valid-to nil}}
           (set
             (xt/q tu/*node*
                   '(from :users [first-name last-name
                                  xt/valid-from xt/valid-to]))))))

;; tag::DML-Delete-xtql[]
(defn delete-a-post [node the-post-id]
  (xt/submit-tx node
    [(-> (xt/delete-from :comments '[{:post-id $post-id}])
         (xt/with-op-args {:post-id the-post-id}))]))
;; end::DML-Delete-xtql[]

(deftest DML-Delete-xtql
  (xt/submit-tx tu/*node* comments)

  (delete-a-post tu/*node* 1)

  (t/is (empty? (xt/q tu/*node* '(from :comments [{:post-id $post-id}])
                      {:args {:post-id 1}})))

  (t/is (not (empty?
               (xt/q tu/*node* '(from :comments {:bind [{:post-id $post-id}]
                                                 :for-valid-time :all-time})
                     {:args {:post-id 1}})))))

(deftest DML-Delete-sql
  (xt/submit-tx tu/*node* comments)

  (xt/submit-tx tu/*node*
    [(-> (xt/sql-op (sql-example "DML-Delete-sql"))
         (xt/with-op-args [1]))])

  (t/is (empty? (xt/q tu/*node* '(from :comments [{:post-id $post-id}])
                      {:args {:post-id 1}})))

  (t/is (not (empty?
               (xt/q tu/*node* '(from :comments {:bind [{:post-id $post-id}]
                                                 :for-valid-time :all-time})
                     {:args {:post-id 1}})))))

(deftest DML-Delete-additional-unify-clauses-xtql
  (xt/submit-tx tu/*node* (concat posts comments))

  (let [node tu/*node*]
    ;; tag::DML-Delete-additional-unify-clauses-xtql[]
    (xt/submit-tx node
      [(-> (xt/delete-from :comments '[{:post-id pid}]
                           '(from :posts [{:xt/id pid, :author-id $author}]))
           (xt/with-op-args {:author "ivan"}))])
    ;; end::DML-Delete-additional-unify-clauses-xtql[]
    ,)

  (t/is (empty? (xt/q tu/*node*
                      '(unify (from :comments [{:post-id pid}])
                              (from :posts [{:xt/id pid, :author-id $author}]))
                      {:args {:author "ivan"}})))

  (t/is (not (empty?
               (xt/q tu/*node*
                     '(unify (from :comments {:bind [{:post-id pid}]
                                              :for-valid-time :all-time})
                             (from :posts [{:xt/id pid, :author-id $author}]))
                     {:args {:author "ivan"}})))))

(deftest DML-Delete-additional-unify-clauses-sql
  (xt/submit-tx tu/*node* (concat posts comments))

  (xt/submit-tx tu/*node*
    [(-> (xt/sql-op (sql-example "DML-Delete-additional-unify-clauses-sql"))
         (xt/with-op-args ["ivan"]))])

  (t/is (empty? (xt/q tu/*node*
                      '(unify (from :comments [{:post-id pid}])
                              (from :posts [{:xt/id pid, :author-id $author}]))
                      {:args {:author "ivan"}})))

  (t/is (not (empty?
               (xt/q tu/*node*
                     '(unify (from :comments {:bind [{:post-id pid}]
                                              :for-valid-time :all-time})
                             (from :posts [{:xt/id pid, :author-id $author}]))
                     {:args {:author "ivan"}})))))

(deftest DML-Delete-bitemporal-xtql
  (xt/submit-tx tu/*node* promotions)

  (t/is (= #{{:promotion-type "christmas"}
             {:promotion-type "general"}}
           (set
             (xt/q tu/*node*
                   '(unify (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-25")})
                           (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-26")}))))))

  (let [node tu/*node*]
    ;; tag::DML-Delete-bitemporal-xtql[]
    (xt/submit-tx node
      [(xt/delete-from :promotions '{:bind [{:promotion-type "christmas"}]
                                     :for-valid-time (from #inst "2023-12-26")})])
    ;; end::DML-Delete-bitemporal-xtql[]
    ,)

  (t/is (= #{{:promotion-type "general"}}
           (set
             (xt/q tu/*node*
                   '(unify (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-25")})
                           (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-26")})))))))

(deftest DML-Delete-bitemporal-sql
  (xt/submit-tx tu/*node* promotions)

  (t/is (= #{{:promotion-type "christmas"}
             {:promotion-type "general"}}
           (set
             (xt/q tu/*node*
                   '(unify (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-25")})
                           (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-26")}))))))

  (xt/submit-tx tu/*node*
    [(xt/sql-op (sql-example "DML-Delete-bitemporal-sql"))])

  (t/is (= #{{:promotion-type "general"}}
           (set
             (xt/q tu/*node*
                   '(unify (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-25")})
                           (from :promotions {:bind [promotion-type]
                                              :for-valid-time (from #inst "2023-12-26")})))))))

(deftest DML-Delete-everything-xtql
  (xt/submit-tx tu/*node* comments)

  (t/is (not (empty? (xt/q tu/*node* '(from :comments [])))))

  (let [node tu/*node*]
    ;; tag::DML-Delete-everything-xtql[]
    (xt/submit-tx node
      [(xt/delete-from :comments '{})])
    ;; end::DML-Delete-everything-xtql[]
    ,)

  (t/is (empty? (xt/q tu/*node* '(from :comments []))))

  (t/is (not (empty? (xt/q tu/*node* '(from :comments {:bind [] :for-valid-time :all-time}))))))

(deftest DML-Delete-everything-sql
  (xt/submit-tx tu/*node* comments)

  (t/is (not (empty? (xt/q tu/*node* '(from :comments [])))))

  (xt/submit-tx tu/*node*
    [(xt/sql-op (sql-example "DML-Delete-everything-sql"))])

  (t/is (empty? (xt/q tu/*node* '(from :comments []))))

  (t/is (not (empty? (xt/q tu/*node* '(from :comments {:bind [] :for-valid-time :all-time}))))))

(deftest DML-Update-xtql
  (xt/submit-tx tu/*node*
    [(xt/put :documents {:xt/id "doc-id", :version 1})])

  (t/is (= [{:version 1}]
           (xt/q tu/*node* '(from :documents [version]))))

  (let [node tu/*node*]
    ;; tag::DML-Update-xtql[]
    (xt/submit-tx node
      [(-> (xt/update-table :documents '{:bind [{:xt/id $doc-id, :version v}]
                                         :set {:version (+ v 1)}})
           (xt/with-op-args {:doc-id "doc-id"}))])
    ;; end::DML-Update-xtql[]
    ,)

  (t/is (= [{:version 2}]
           (xt/q tu/*node* '(from :documents [version])))))

(deftest DML-Update-sql
  (xt/submit-tx tu/*node*
    [(xt/put :documents {:xt/id "doc-id", :version 1})])

  (t/is (= [{:version 1}]
           (xt/q tu/*node* '(from :documents [version]))))

  (xt/submit-tx tu/*node*
    [(-> (xt/sql-op (sql-example "DML-Update-sql"))
         (xt/with-op-args ["doc-id"]))])

  (t/is (= [{:version 2}]
           (xt/q tu/*node* '(from :documents [version])))))

(deftest DML-Update-bitemporal-xtql
  (xt/submit-tx tu/*node*
    [(xt/put :posts {:xt/id "my-post-id" :comment-count 1})
     (xt/put :comments {:xt/id 1, :post-id "my-post-id"})])

  (t/is (= [{:comment-count 1}]
           (xt/q tu/*node* '(from :posts [comment-count]))))
  
  (let [node tu/*node*]
    ;; tag::DML-Update-bitemporal-xtql[]
    (xt/submit-tx node
      [(xt/put :comments {:xt/id (random-uuid), :post-id "my-post-id"})
       (-> (xt/update-table :posts '{:bind [{:xt/id $post-id}], :set {:comment-count cc}}
                            '(with {cc (q (-> (from :comments [{:post-id $post-id}])
                                              (aggregate {:cc (row-count)})))}))
           (xt/with-op-args {:post-id "my-post-id"}))])
    ;; end::DML-Update-bitemporal-xtql[]
    ,)

  (t/is (= [{:comment-count 2}]
           (xt/q tu/*node* '(from :posts [comment-count])))))

#_ ;; TODO: Uncomment when supported: https://github.com/xtdb/xtdb/issues/3050
(deftest DML-Update-bitemporal-sql
  (xt/submit-tx tu/*node*
    [(xt/put :posts {:xt/id "my-post-id" :comment-count 1})
     (xt/put :comments {:xt/id 1, :post-id "my-post-id"})])

  (t/is (= [{:comment-count 1}]
           (xt/q tu/*node* '(from :posts [comment-count]))))
  
  (xt/submit-tx tu/*node*
    [(-> (xt/sql-op (sql-example "DML-Update-bitemporal-sql-1"))
         (xt/with-op-args [(random-uuid), "my-post-id"]))
     (-> (xt/sql-op (sql-example "DML-Update-bitemporal-sql-2"))
         (xt/with-op-args ["my-post-id" "my-post-id"]))])

  (t/is (= [{:comment-count 2}]
           (xt/q tu/*node* '(from :posts [comment-count])))))

(deftest DML-Erase-xtql
  (xt/submit-tx tu/*node*
    [(xt/put :users {:xt/id "user-id", :email "jms@example.com"})])

  (t/is (not (empty? (xt/q tu/*node* '(from :users [])))))

  (let [node tu/*node*]
    ;; tag::DML-Erase-xtql[]
    (xt/submit-tx node
      [(xt/erase-from :users '[{:email "jms@example.com"}])])
    ;; end::DML-Erase-xtql[]
    ,)

  (t/is (empty? (xt/q tu/*node* '(from :users []))))
  (t/is (empty? (xt/q tu/*node* '(from :users {:bind [] :for-valid-time :all-time})))))

(deftest DML-Erase-sql
  (xt/submit-tx tu/*node*
    [(xt/put :users {:xt/id "user-id", :email "jms@example.com"})])

  (t/is (not (empty? (xt/q tu/*node* '(from :users [])))))

  (xt/submit-tx tu/*node*
    [(xt/sql-op (sql-example "DML-Erase-sql"))])

  (t/is (empty? (xt/q tu/*node* '(from :users []))))
  (t/is (empty? (xt/q tu/*node* '(from :users {:bind [] :for-valid-time :all-time})))))

(deftest DML-Assert
  (xt/submit-tx tu/*node*
    [(xt/put :users {:xt/id :james, :email "james@example.com"})])

  (let [node tu/*node*
        {my-tx-id :tx-id}
        ;; tag::DML-Assert-xtql[]
        (xt/submit-tx node
          [(-> (xt/assert-not-exists '(from :users [{:email $email}]))
               (xt/with-op-args {:email "james@example.com"}))

           (xt/put :users {:xt/id :james, :email "james@example.com"})])
        ;; end::DML-Assert-xtql[]
        ,]
    (t/is (= ;; tag::DML-Assert-query-result[]
             [{:xt/committed? false
               :xt/error {::err/error-type :runtime-error
                          ::err/error-key :xtdb/assert-failed
                          ::err/message "Precondition failed: assert-not-exists"
                          :row-count 1}}]
             ;; end::DML-Assert-query-result[]
             (-> ;; tag::DML-Assert-query[]
                 (xt/q node '(from :xt/txs [{:xt/id $tx-id} xt/committed? xt/error])
                      {:args {:tx-id my-tx-id}})
                 ;; end::DML-Assert-query[]
                 (update-in [0 :xt/error] ex-data)))))

  ;; TODO: Once implemented
  #_
  (let [{:keys [tx-id]}
        (xt/submit-tx tu/*node*
          [(xt/sql-op (sql-example "DML-Assert-sql"))])]
    (t/is (= [{:xt/committed? false}]
             (xt/q tu/*node* '(from :xt/txs [{:xt/id $tx-id} xt/committed?])
                   {:args {:tx-id tx-id}})))))
