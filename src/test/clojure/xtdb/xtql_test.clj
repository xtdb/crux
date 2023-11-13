;; THIRD-PARTY SOFTWARE NOTICE
;; This file is derivative of test files found in the DataScript project.
;; The Datascript license is copied verbatim in this directory as `LICENSE`.
;; https://github.com/tonsky/datascript

(ns xtdb.xtql-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.james-bond :as bond]
            [xtdb.test-util :as tu]
            [xtdb.util :as util]
            [xtdb.node :as node])
  (:import (xtdb.types ClojureForm)))

(t/use-fixtures :each tu/with-mock-clock tu/with-node)

(def ivan+petr
  '[[:put :docs {:xt/id :ivan, :first-name "Ivan", :last-name "Ivanov"}]
    [:put :docs {:xt/id :petr, :first-name "Petr", :last-name "Petrov"}]])

(deftest test-from
  (xt/submit-tx tu/*node* ivan+petr)

  (t/is (= #{{:first-name "Ivan"}
             {:first-name "Petr"}}
           (set (xt/q tu/*node* '(from :docs [first-name])))))

  (t/is (= #{{:name "Ivan"}
             {:name "Petr"}}
           (set (xt/q tu/*node* '(from :docs [{:first-name name}])))))

  (t/is (= #{{:e :ivan, :name "Ivan"}
             {:e :petr, :name "Petr"}}
           (set (xt/q tu/*node*
                      '(from :docs [{:xt/id e, :first-name name}]))))
        "returning eid")

  (t/is (= #{{:name "Ivan" :also-name "Ivan"}
             {:name "Petr" :also-name "Petr"}}
           (set (xt/q tu/*node* '(from :docs [{:first-name name} {:first-name also-name}]))))
        "projecting col out multiple times to different vars"))

(deftest test-from-unification
  (xt/submit-tx tu/*node*
                (conj ivan+petr
                      [:put :docs {:xt/id :jeff, :first-name "Jeff", :last-name "Jeff"}]))

  (t/is (= #{{:name "Jeff"}}
           (set (xt/q tu/*node* '(from :docs [{:first-name name :last-name name}])))))

  (t/is (= #{{:name "Jeff"} {:name "Petr"} {:name "Ivan"}}
           (set (xt/q tu/*node* '(from :docs [{:first-name name} {:first-name name}]))))
        "unifying over the same column, for completeness rather than utility"))

(deftest test-basic-query
  (xt/submit-tx tu/*node* ivan+petr)

  (t/is (= [{:e :ivan}]
           (xt/q tu/*node*
                 '(from :docs [{:xt/id e, :first-name "Ivan"}])))
        "query by single field")

  ;; HACK this scans first-name out twice
  (t/is (= [{:first-name "Petr", :last-name "Petrov"}]
           (xt/q tu/*node*
                 '(from :docs [{:first-name "Petr"} first-name last-name])))
        "returning the queried field")

  (t/is (= [{:first-name "Petr", :last-name "Petrov"}]
           (xt/q tu/*node*
                 '(from :docs [{:xt/id :petr} first-name last-name])))
        "literal eid"))

(deftest test-table
  (xt/submit-tx tu/*node* ivan+petr)

  (t/is (= [{} {}]
           (xt/q tu/*node* '(table [{:first-name "Ivan"} {:first-name "Petr"}] []))))

  (t/is (= #{{:first-name "Ivan"} {:first-name "Petr"}}
           (set (xt/q tu/*node* '(table [{:first-name "Ivan"} {:first-name "Petr"}] [first-name])))))

  (t/is (= [{:first-name "Petr"}]
           (xt/q tu/*node* '(-> (table [{:first-name "Ivan"} {:first-name "Petr"}] [first-name])
                                (where (= "Petr" first-name))))))

  #_ ;; TODO literal out specs
  (t/is (= [{:first-name "Petr"}]
           (xt/q tu/*node* '(-> (table [{:first-name "Ivan"} {:first-name "Petr"}] {:bind [{:xt/id :petr} first-name]})
                                (where (= "Petr" first-name))))))

  (t/is (= [{:first-name "Ivan"} {:first-name "Petr"}]
           (xt/q tu/*node*
                 '(table [{:first-name "Ivan"} {:first-name $petr}] [first-name])
                 {:args {:petr "Petr"}}))
        "simple param")

  (t/is (= [{:foo :bar, :baz {:nested-foo :bar}}]
           (xt/q tu/*node*
                 '(table [{:foo :bar :baz {:nested-foo $nested-param}}] [foo baz])
                 {:args {:nested-param :bar}}))
        "simple param nested")

  (t/is (= #{{:first-name "Ivan"} {:first-name "Petr"}}
           (set (xt/q tu/*node* '(unify (from :docs {:bind [first-name]})
                                        (table [{:first-name "Ivan"} {:first-name "Petr"}] [first-name])))))
        "testing unify")

  (t/is (= [{:first-name "Petr"}]
           (xt/q tu/*node* '(unify (from :docs {:bind [{:xt/id :petr} first-name]})
                                   (table [{:first-name "Ivan"} {:first-name "Petr"}] [first-name]))))
        "testing unify with restriction")

  #_ ;; TODO still some issues with normalisation
  (t/is (= #{{:first-name "Ivan"} {:first-name "Petr"}}
           (set (xt/q tu/*node* '(table $ivan+petr {:bind [first-name]})
                      {:args {:ivan+petr [{:first-name "Ivan"} {:first-name "Petr"}]}})))
        "table arg as parameter")

  (t/is (= #{{:name "Ivan"} {:name "Petr"}}
           (set (xt/q tu/*node* '(table $ivan+petr [name])
                      {:args {:ivan+petr [{:name "Ivan"} {:name "Petr"}]}})))
        "table arg as parameter")

  (t/is (= #{{:name "Petr"}}
           (set (xt/q tu/*node* '(-> (table $ivan+petr [name])
                                     (where (= "Petr" name)))
                      {:args {:ivan+petr [{:name "Ivan"} {:name "Petr"}]}})))
        "table arg as parameter")

  (t/is (= [{:name "Petr"}]
           (xt/q tu/*node* '(unify (from :docs [{:xt/id :petr :first-name name}])
                                   (table $ivan+petr [name]))
                 {:args {:ivan+petr [{:name "Ivan"} {:name "Petr"}]}}))
        "table arg as paramater in unify"))


(deftest test-order-by
  (let [_tx (xt/submit-tx tu/*node* ivan+petr)]
    (t/is (= [{:first-name "Ivan"} {:first-name "Petr"}]
             (xt/q tu/*node*
                   '(-> (from :docs [first-name])
                        (order-by first-name)))))

    (t/is (= [{:first-name "Petr"} {:first-name "Ivan"}]
             (xt/q tu/*node*
                   '(-> (from :docs [first-name])
                        (order-by [first-name {:dir :desc}])))))


    (t/is (= [{:first-name "Ivan"}]
             (xt/q tu/*node*
                   '(-> (from :docs [first-name])
                        (order-by first-name)
                        (limit 1)))))


    (t/is (= [{:first-name "Petr"}]
             (xt/q tu/*node*
                   '(-> (from :docs [first-name])
                        (order-by [first-name {:dir :desc}])
                        (limit 1)))))

    (t/is (= [{:first-name "Petr"}]
             (xt/q tu/*node*
                   '(-> (from :docs [first-name])
                        (order-by first-name)
                        (offset 1)
                        (limit 1)))))))

(deftest test-order-by-multiple-cols
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id 2, :n 2}]
                            [:put :docs {:xt/id 3, :n 3}]
                            [:put :docs {:xt/id 1, :n 2}]
                            [:put :docs {:xt/id 4, :n 1}]])]
    (t/is (= [{:i 4, :n 1}
              {:i 1, :n 2}
              {:i 2, :n 2}
              {:i 3, :n 3}]
             (xt/q tu/*node*
                   '(-> (from :docs [{:xt/id i, :n n}])
                        (order-by n i)))))))

;; https://github.com/tonsky/datascript/blob/1.1.0/test/datascript/test/query.cljc#L12-L36
(deftest datascript-test-unify
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id 1, :name "Ivan", :age 15}]
                            [:put :docs {:xt/id 2, :name "Petr", :age 37}]
                            [:put :docs {:xt/id 3, :name "Ivan", :age 37}]
                            [:put :docs {:xt/id 4, :age 15}]])]

    (t/is (= #{{:e 1, :v 15} {:e 3, :v 37}}
             (set (xt/q tu/*node*
                        '(from :docs [{:xt/id e, :name "Ivan", :age v}])))))

    (t/is (= #{{:e1 1, :e2 3} {:e1 3, :e2 1}}
             (set (xt/q tu/*node*
                        '(-> (unify (from :docs [{:xt/id e1, :name n}])
                                    (from :docs [{:xt/id e2, :name n}])
                                    (where (<> e1 e2)))
                             (without :n))))))


    (t/is (= #{{:e 1, :e2 1, :n "Ivan"}
               {:e 3, :e2 3, :n "Ivan"}
               {:e 3, :e2 2, :n "Petr"}
               {:e 1, :e2 4}}
             (set (xt/q tu/*node*
                        '(-> (unify (from :docs [{:xt/id e, :name "Ivan", :age a}])
                                    (from :docs [{:xt/id e2, :name n, :age a}]))
                             (without :a))))))

    (t/is (= #{{:e 1, :e2 1, :n "Ivan"}
               {:e 2, :e2 2, :n "Petr"}
               {:e 3, :e2 3, :n "Ivan"}
               {:e 4, :e2 4}}
             (set (xt/q tu/*node*
                        '(-> (unify (from :docs [{:xt/id e, :name n, :age a}])
                                    (from :docs [{:xt/id e2, :name n, :age a}]))
                             (without :a)))))
          "multi-param join")

    (t/is (= #{{:e1 1, :e2 1, :a1 15, :a2 15}
               {:e1 1, :e2 3, :a1 15, :a2 37}
               {:e1 3, :e2 1, :a1 37, :a2 15}
               {:e1 3, :e2 3, :a1 37, :a2 37}}
             (set (xt/q tu/*node*
                        '(unify (from :docs [{:xt/id e1, :name "Ivan", :age a1}])
                                (from :docs [{:xt/id e2, :name "Ivan", :age a2}])))))

          "cross join required here")))

(deftest test-namespaced-attributes
  (let [_tx (xt/submit-tx tu/*node* [[:put :docs {:xt/id :foo :foo/bar 1}]
                                     [:put :docs {:xt/id :bar :foo/bar 2}]])]
    (t/is (= [{:i :foo, :n 1} {:i :bar, :n 2}]
             (xt/q tu/*node*
                   '(from :docs [{:xt/id i :foo/bar n}])))
          "simple query with namespaced attributes")
    (t/is (= [{:i/i :foo, :n/n 1} {:i/i :bar, :n/n 2}]
             (xt/q tu/*node*
                   '(-> (from :docs [{:xt/id i :foo/bar n}])
                        (return {:i/i i :n/n n}))))
          "query with namespaced keys")
    (t/is (= [{:i :foo, :n 1 :foo/bar 1} {:i :bar, :n 2 :foo/bar 2}]
             (xt/q tu/*node*
                   '(from :docs [{:xt/id i :foo/bar n} foo/bar])))
          "query with namespaced attributes in match syntax")))

(deftest test-unify
  (xt/submit-tx tu/*node* bond/tx-ops)

  (t/is (= [{:bond :daniel-craig}]
           (xt/q tu/*node*
                 '(unify (from :person [{:xt/id bond, :person/name "Daniel Craig"}])))))

  ;;TODO Params rewritten using with clause, make sure params are tested elsewhere
  (t/is (= #{{:film-name "Skyfall", :bond-name "Daniel Craig"}}
           (set (xt/q tu/*node*
                      '(-> (unify (from :film [{:xt/id film, :film/name film-name, :film/bond bond}])
                                  (from :person [{:xt/id bond, :person/name bond-name}])
                                  (with {film :skyfall}))
                           (return :film-name :bond-name)))))
        "one -> one")


  (t/is (= #{{:film-name "Casino Royale", :bond-name "Daniel Craig"}
             {:film-name "Quantum of Solace", :bond-name "Daniel Craig"}
             {:film-name "Skyfall", :bond-name "Daniel Craig"}
             {:film-name "Spectre", :bond-name "Daniel Craig"}}
           (set (xt/q tu/*node*
                      '(-> (unify (from :film [{:film/name film-name, :film/bond bond}])
                                  (from :person [{:xt/id bond, :person/name bond-name}])
                                  (with {bond :daniel-craig}))
                           (return :film-name :bond-name)))))
        "one -> many"))

;; https://github.com/tonsky/datascript/blob/1.1.0/test/datascript/test/query_aggregates.cljc#L14-L39

(t/deftest datascript-test-aggregates
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id :cerberus, :heads 3}]
                            [:put :docs {:xt/id :medusa, :heads 1}]
                            [:put :docs {:xt/id :cyclops, :heads 1}]
                            [:put :docs {:xt/id :chimera, :heads 1}]])]
    (t/is (= #{{:heads 1, :count-heads 3} {:heads 3, :count-heads 1}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [heads])
                             (aggregate :heads {:count-heads (count heads)})))))
          "head frequency")

    (t/is (= #{{:sum-heads 6, :min-heads 1, :max-heads 3, :count-heads 4}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [heads])
                             (aggregate {:sum-heads (sum heads)
                                         :min-heads (min heads)
                                         :max-heads (max heads)
                                         :count-heads (count heads)})))))
          "various aggs")))

(t/deftest test-with-op
  (let [_tx (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :o1, :unit-price 1.49, :quantity 4}]
                                      [:put :docs {:xt/id :o2, :unit-price 5.39, :quantity 1}]
                                      [:put :docs {:xt/id :o3, :unit-price 0.59, :quantity 7}]])]
    (t/is (= #{{:oid :o1, :o-value 5.96, :unit-price 1.49, :qty 4}
               {:oid :o2, :o-value 5.39, :unit-price 5.39, :qty 1}
               {:oid :o3, :o-value 4.13, :unit-price 0.59, :qty 7}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id oid :unit-price unit-price :quantity qty}])
                             (with {:o-value (* unit-price qty)}))))))))

(t/deftest test-with-op-errs
  (let [_tx (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :foo}]])]
    (t/is (thrown-with-msg? IllegalArgumentException
                            #"Not all variables in expression are in scope"
                            (xt/q tu/*node*
                                  '(-> (from :docs [{:xt/id id}])
                                       (with {:bar (str baz)})))))))

#_
(deftest test-aggregate-exprs
  (let [tx (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :foo, :category :c0, :v 1}]
                                     [:put :docs {:xt/id :bar, :category :c0, :v 2}]
                                     [:put :docs {:xt/id :baz, :category :c1, :v 4}]])]
    (t/is (= [{:category :c0, :sum-doubles 6}
              {:category :c1, :sum-doubles 8}]
             (xt/q tu/*node*
                   '{:find [category (sum (* 2 v))]
                     :keys [category sum-doubles]
                     :where [(match :docs {:xt/id e})
                             [e :category category]
                             [e :v v]]}))))

  (t/is (= [{:x 0, :sum-y 0, :sum-expr 1}
            {:x 1, :sum-y 1, :sum-expr 5}
            {:x 2, :sum-y 3, :sum-expr 14}
            {:x 3, :sum-y 6, :sum-expr 30}]
           (xt/q tu/*node*
                 ['{:find [x (sum y) (sum (+ (* y y) x 1))]
                    :keys [x sum-y sum-expr]
                    :in [[[x y]]]}
                  (for [x (range 4)
                        y (range (inc x))]
                    [x y])])))

  (t/is (= [{:sum-evens 20}]
           (xt/q tu/*node*
                 ['{:find [(sum (if (= 0 (mod x 2)) x 0))]
                    :keys [sum-evens]
                    :in [[x ...]]}
                  (range 10)]))
        "if")

  ;; TODO aggregates nested within other aggregates/forms
  ;; - doesn't appear in TPC-H but guess we'll want these eventually

  #_
  (t/is (= #{[28.5]}
           (xt/q (xt/db *api*)
                 ['{:find [(/ (double (sum (* ?x ?x)))
                              (count ?x))]
                    :in [[?x ...]]}
                  (range 10)]))
        "aggregates can be included in exprs")

  #_
  (t/is (thrown-with-msg? IllegalArgumentException
                          #"nested agg"
                          (xt/q (xt/db *api*)
                                ['{:find [(sum (sum ?x))]
                                   :in [[?x ...]]}
                                 (range 10)]))
        "aggregates can't be nested")

  (t/testing "implicitly groups by variables present outside of aggregates"
    (t/is (= [{:x-div-y 1, :sum-z 2} {:x-div-y 2, :sum-z 3} {:x-div-y 2, :sum-z 5}]
             (xt/q tu/*node*
                   ['{:find [(/ x y) (sum z)]
                      :keys [x-div-y sum-z]
                      :in [[[x y z]]]}
                    [[1 1 2]
                     [2 1 3]
                     [4 2 5]]]))
          "even though (/ x y) yields the same result in the latter two rows, we group by them individually")

    #_
    (t/is (= #{[1 3] [1 7] [4 -1]}
             (xt/q tu/*node*
                   ['{:find [x (- (sum z) y)]
                      :in [[[x y z]]]}
                    [[1 1 4]
                     [1 3 2]
                     [1 3 8]
                     [4 6 5]]]))
          "groups by x and y in this case"))

  (t/testing "stddev aggregate"
    (t/is (= [{:y 23.53720459187964}]
             (xt/q tu/*node*
                   ['{:find [(stddev-pop x)]
                      :keys [y]
                      :in [[x ...]]}
                    [10 15 20 35 75]])))))

(deftest test-composite-value-bindings
  (xt/submit-tx tu/*node* [[:put :docs {:xt/id 1 :map {:foo 1} :set #{1 2 3}}]])

  (t/is (= [{:xt/id 1}]
           (xt/q tu/*node* '(from :docs [xt/id {:map {:foo 1}}]))))

  #_ ; TODO `=` on sets
  (t/is (= [{:xt/id 1}]
           (xt/q tu/*node* '(from :docs [xt/id {:set #{1 2 3}}]))))

  (t/is (= [{:xt/id 1}]
           (xt/q tu/*node* '(from :docs [xt/id {:map {:foo $param}}])
                 {:args {:param 1}})))

  #_ ; TODO `=` on sets
  (t/is (= [{:xt/id 1}]
           (xt/q tu/*node* '(from :docs [xt/id {:set $param}])
                 {:args {:param #{1 2 3}}}))))

(deftest test-query-args
  (let [_tx (xt/submit-tx tu/*node* ivan+petr)]
    (t/is (= #{{:e :ivan}}
               (set (xt/q tu/*node*
                          '(from :docs [{:xt/id e :first-name $name}])
                          {:args {:name "Ivan"}})))

            "param in from")

    (t/is (= #{{:e :petr :name "Petr"}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id e :first-name name}])
                             (where (= $name name)))
                        {:args {:name "Petr"}})))
          "param in where op")

    (t/is (= #{{:e :petr :name "Petr" :baz "PETR"}
               {:e :ivan :name "Ivan" :baz "PETR"}}
             (set (xt/q tu/*node*
                        '(unify (from :docs [{:xt/id e :first-name name}])
                                (with {baz (upper $name)}))
                        {:args {:name "Petr"}})))
          "param in unify with")


    #_#_#_#_#_(t/is (= #{{:e :ivan}}
                       (set (xt/q tu/*node*
                                  ['{:find [e]
                                     :in [first-name last-name]
                                     :where [(match :docs {:xt/id e})
                                             [e :first-name first-name]
                                             [e :last-name last-name]]}
                                   "Ivan" "Ivanov"])))
                    "multiple args")

    (t/is (= #{{:e :ivan}}
             (set (xt/q tu/*node*
                        ['{:find [e]
                           :in [[first-name]]
                           :where [(match :docs {:xt/id e})
                                   [e :first-name first-name]]}
                         ["Ivan"]])))
          "tuple with 1 var")

    (t/is (= #{{:e :ivan}}
             (set (xt/q tu/*node*
                        ['{:find [e]
                           :in [[first-name last-name]]
                           :where [(match :docs {:xt/id e})
                                   [e :first-name first-name]
                                   [e :last-name last-name]]}
                         ["Ivan" "Ivanov"]])))
          "tuple with 2 vars")

    (t/testing "collection"
      (let [query '{:find [e]
                    :in [[first-name ...]]
                    :where [(match :docs {:xt/id e})
                            [e :first-name first-name]]}]
        (t/is (= #{{:e :petr}}
                 (set (xt/q tu/*node* [query ["Petr"]]))))

        (t/is (= #{{:e :ivan} {:e :petr}}
                 (set (xt/q tu/*node* [query ["Ivan" "Petr"]]))))))

    (t/testing "relation"
      (let [query '{:find [e]
                    :in [[[first-name last-name]]]
                    :where [(match :docs {:xt/id e})
                            [e :first-name first-name]
                            [e :last-name last-name]]}]

        (t/is (= #{{:e :ivan}}
                 (set (xt/q tu/*node*
                            [query [{:first-name "Ivan", :last-name "Ivanov"}]]))))

        (t/is (= #{{:e :ivan}}
                 (set (xt/q tu/*node*
                            [query [["Ivan" "Ivanov"]]]))))

        (t/is (= #{{:e :ivan} {:e :petr}}
                 (set (xt/q tu/*node*
                            [query
                             [{:first-name "Ivan", :last-name "Ivanov"}
                              {:first-name "Petr", :last-name "Petrov"}]]))))

        (t/is (= #{{:e :ivan} {:e :petr}}
                 (set (xt/q tu/*node*
                            [query
                             [["Ivan" "Ivanov"]
                              ["Petr" "Petrov"]]]))))))))

#_
(deftest test-in-arity-exceptions
  (let [_tx (xt/submit-tx tu/*node* ivan+petr)]
    (t/is (thrown-with-msg? IllegalArgumentException
                            #":in arity mismatch"
                            (xt/q tu/*node*
                                  '{:find [e]
                                    :in [foo]
                                    :where [(match :docs {:xt/id e})
                                            [e :foo foo]]})))))
(deftest test-subquery-args-and-unification
  (let [_tx (xt/submit-tx tu/*node* ivan+petr)]
    (t/is (= #{{:name "Ivan" :last-name "Ivanov"}}
             (set (xt/q tu/*node*
                        '(unify
                          (with {name "Ivan"})
                          (join (from :docs [{:xt/id e
                                              :first-name $name
                                              :last-name last-name}])
                                {:args [name]
                                 :bind [last-name]})))))

          "single subquery")

    (t/is (= #{{:name "Ivan" :last-name "Ivanov"}}
             (set (xt/q tu/*node*
                        '(unify
                          (with {name "Ivan"})
                          (join (from :docs [{:xt/id e
                                              :first-name $name
                                              :last-name last-name}])
                                {:args [name]
                                 :bind [last-name]})

                          (join (from :docs [{:xt/id e
                                              :first-name $f-name
                                              :last-name last-name}])
                                {:args [{:f-name name}]
                                 :bind [last-name]})))))

          "multiple subqueries with unique args but same bind")))

(deftest test-where-op
  (let [_tx (xt/submit-tx tu/*node* ivan+petr)]
    (t/is (= #{{:first-name "Ivan", :last-name "Ivanov"}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:first-name first-name :last-name last-name}])
                             (where (< first-name "James")))))))

    (t/is (= #{{:first-name "Ivan", :last-name "Ivanov"}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:first-name first-name :last-name last-name}])
                             (where (<= first-name "Ivan")))))))

    (t/is (empty? (xt/q tu/*node*
                        '(-> (from :docs [{:first-name first-name :last-name last-name}])
                             (where (<= first-name "Ivan")
                                    (> last-name "Ivanov"))))))

    (t/is (empty (xt/q tu/*node*
                       '(-> (from :docs [{:first-name first-name :last-name last-name}])
                            (where (< first-name "Ivan"))))))))

#_
(deftest test-value-unification
  (let [_tx (xt/submit-tx tu/*node*
                          (conj ivan+petr
                                '[:put :docs {:xt/id :sergei :first-name "Sergei" :last-name "Sergei"}]
                                '[:put :docs {:xt/id :jeff :first-name "Sergei" :last-name "but-different"}]))]
    (t/is (= [{:e :sergei, :n "Sergei"}]
             (xt/q
              tu/*node*
              '{:find [e n]
                :where [(match :docs {:xt/id e})
                        [e :last-name n]
                        [e :first-name n]]})))
    (t/is (= #{{:e :sergei, :f :sergei, :n "Sergei"} {:e :sergei, :f :jeff, :n "Sergei"}}
             (set (xt/q
                   tu/*node*
                   '{:find [e f n]
                     :where [(match :docs {:xt/id e})
                             (match :docs {:xt/id f})
                             [e :last-name n]
                             [e :first-name n]
                             [f :first-name n]]}))))))

#_
(deftest test-implicit-match-unification
  (xt/submit-tx tu/*node* '[[:put :foo {:xt/id :ivan, :name "Ivan"}]
                            [:put :foo {:xt/id :petr, :name "Petr"}]
                            [:put :bar {:xt/id :sergei, :name "Sergei"}]
                            [:put :bar {:xt/id :ivan,:name "Ivan"}]
                            [:put :toto {:xt/id :jon, :name "John"}]
                            [:put :toto {:xt/id :mat,:name "Matt"}]])
  (t/is (= [{:e :ivan, :name "Ivan"}]
           (xt/q tu/*node*
                 '{:find [e name]
                   :where [(match :foo {:xt/id e})
                           (match :bar {:xt/id e})
                           [e :name name]]})))
  (t/is (= []
           (xt/q tu/*node*
                 '{:find [e]
                   :where [(match :foo {:xt/id e})
                           (match :toto {:xt/id e})
                           [e :name]]}))))

#_
(deftest exists-with-no-outer-unification-692
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id :ivan, :name "Ivan"}]
                            [:put :docs {:xt/id :petr, :name "Petr"}]
                            [:put :docs {:xt/id :ivan2, :name "Ivan"}]])]

    (t/is (= [{:e :ivan} {:e :ivan2}]
             (xt/q tu/*node*
                   '{:find [e]
                     :where [(match :docs {:xt/id e})
                             (exists? {:find [e2]
                                       :in [e]
                                       :where [(match :docs {:xt/id e})
                                               (match :docs {:xt/id e2})
                                               [e :name name]
                                               [e2 :name name]
                                               [(<> e e2)]]})]}))
          "with in variables")
    (t/is (= [{:e :ivan} {:e :ivan2}]
             (xt/q tu/*node*
                   '{:find [e]
                     :where [(match :docs {:xt/id e})
                             (match :docs {:xt/id e2})
                             (exists? {:find [e e2]
                                       :where [(match :docs {:xt/id e})
                                               (match :docs {:xt/id e2})
                                               [e :name name]
                                               [e2 :name name]
                                               [(<> e e2)]]})]}))
          "without in variables")))

#_
(deftest exists-with-keys-699
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id 1, :name "one"}]
                            [:put :docs {:xt/id 2, :name "two"}]
                            [:put :docs {:xt/id 3, :name "three"}]])]

    (t/is (= [{:e 2} {:e 3}]
             (xt/q tu/*node*
                   '{:find [e]
                     :where [(match :docs {:xt/id e})
                             (match :docs {:xt/id e3})
                             (exists? {:find [(+ e2 2)]
                                       :keys [e3]
                                       :in [e]
                                       :where [(match :docs {:xt/id e2})
                                               [(<> e e2)]]})]})))))


(deftest test-left-join
  (xt/submit-tx tu/*node*
                '[[:put :docs {:xt/id :ivan, :name "Ivan"}]
                  [:put :docs {:xt/id :petr, :name "Petr", :parent :ivan}]
                  [:put :docs {:xt/id :sergei, :name "Sergei", :parent :petr}]
                  [:put :docs {:xt/id :jeff, :name "Jeff", :parent :petr}]])

  (t/is (= #{{:e :ivan, :c :petr}
             {:e :petr, :c :sergei}
             {:e :petr, :c :jeff}
             {:e :sergei, :c nil}
             {:e :jeff, :c nil}}
           (set (xt/q tu/*node*
                      '(unify (from :docs [{:xt/id e}])
                              (left-join (from :docs [{:xt/id c :parent e}])
                                         [c e])))))

        "independent: find people who have children")

  (t/is (= #{{:e :ivan, :s nil}
             {:e :petr, :s nil}
             {:e :sergei, :s :jeff}
             {:e :jeff, :s :sergei}}
           (set (xt/q tu/*node*
                      '(-> (unify (from :docs [{:xt/id e, :name name, :parent p}])
                                  (left-join (-> (from :docs [{:xt/id s, :parent p}])
                                                 (where (<> $e s)))
                                             {:args [e]
                                              :bind [s p]}))
                           (return :e :s)))))
        "dependent: find people who have siblings")

  (t/is (thrown-with-msg? IllegalArgumentException
                          #"Not all variables in expression are in scope"
                          (xt/q tu/*node*
                                '(unify (from :docs [{:xt/id e :foo n}])
                                        (left-join
                                         (-> (from :docs [{:xt/id e :first-name "Petr"}])
                                             (where (= n 1)))
                                         [e]))))))

;;TODO some generic tests for unavailable vars/missing params?

(deftest test-exists
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id :ivan, :name "Ivan"}]
                            [:put :docs {:xt/id :petr, :name "Petr", :parent :ivan}]
                            [:put :docs {:xt/id :sergei, :name "Sergei", :parent :petr}]
                            [:put :docs {:xt/id :jeff, :name "Jeff", :parent :petr}]])]

    (t/is (= #{{:x true}}
             (set (xt/q tu/*node*

                        '(unify
                          (with {x (exists? (from :docs [{:xt/id :ivan}]))})))))
          "uncorrelated, contrived and rarely useful")

    (t/is (= #{{:e :ivan} {:e :petr}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id e}])
                             (where (exists? (from :docs [{:parent $e}])
                                             {:args [e]}))))))

          "find people who have children")

    (t/is (= #{{:e :sergei} {:e :jeff}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id e} parent])
                             (where (exists? (-> (from :docs [{:xt/id s, :parent $parent}])
                                                 (where (<> $e s)))
                                             {:args [e parent]}))
                             (without :parent)))))
          "find people who have siblings")))


(deftest test-not-exists
  (let [_tx (xt/submit-tx
             tu/*node*
             '[[:put :docs {:xt/id :ivan, :first-name "Ivan", :last-name "Ivanov" :foo 1}]
               [:put :docs {:xt/id :petr, :first-name "Petr", :last-name "Petrov" :foo 1}]
               [:put :docs {:xt/id :sergei :first-name "Sergei" :last-name "Sergei" :foo 1}]])]

    (t/is (= #{{:e :ivan} {:e :sergei}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id e :foo 1}])
                             (where (not (exists? (from :docs [{:xt/id $e} {:first-name "Petr"}])
                                                  {:args [e]}))))
                        ))))

    (t/is (= []
             (xt/q tu/*node*
                   '(-> (from :docs [{:xt/id e :foo n}])
                        (where (not (exists? (from :docs [{:xt/id $e} {:foo $n}])
                                             {:args [e n]})))))))




    (t/is (= #{{:n 1, :e :ivan} {:n 1, :e :sergei}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id e :foo n}])
                             (where (not (exists? (-> (from :docs [{:xt/id $e :first-name "Petr"}])
                                                      (where (= $n 1)))
                                                  {:args [e n]}))))))))

    (t/is (= #{{:n 1, :e :sergei, :not-exist true}
               {:n 1, :e :petr, :not-exist true}
               {:n 1, :e :ivan, :not-exist true}}
             (set (xt/q tu/*node*
                        '(-> (from :docs [{:xt/id e :foo n}])
                             (with {:not-exist (not (not (exists? (-> (table [{}] [])
                                                                      (where (= $n 1)))
                                                                  {:args [n]})))}))))))

    (t/is (= #{{:n "Petr", :e :petr} {:n "Sergei", :e :sergei}}
             (set (xt/q tu/*node*
                        '{:find [e n]
                          :where [(match :docs {:xt/id e})
                                  [e :first-name n]
                                  (not-exists? {:find []
                                                :in [n]
                                                :where [[(= "Ivan" n)]]})]}))))


    (t/is (= #{{:n "Petr", :e :petr} {:n "Sergei", :e :sergei}}
             (set (xt/q tu/*node*
                        '(unify
                          (from :docs [{:xt/id e :first-name n}])
                          (where (not (exists? (from :docs [{:first-name "Ivan"} {:first-name $n}]) {:args [n]}))))))))





    (t/is (= [{:first-name "Petr", :e :petr}]
             (xt/q tu/*node*
                   '(unify
                     (from :docs [{:xt/id e} first-name])
                     (where (not (exists?
                                  (-> (table [{}] [])
                                      (where (= $first-name "Ivan")))
                                  {:args [first-name]}))
                            (not (exists?
                                  (from :docs [{:xt/id $e :first-name "Sergei"}])
                                  {:args [e]}))))))
          "Multiple not exists")))

#_
(deftest test-simple-literals-in-find
  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :ivan, :age 15}]
                            [:put :docs {:xt/id :petr, :age 22}]
                            [:put :docs {:xt/id :slava, :age 37}]])


  (t/is (= #{{:_column_0 1, :_column_1 "foo", :xt/id :ivan}
             {:_column_0 1, :_column_1 "foo", :xt/id :petr}
             {:_column_0 1, :_column_1 "foo", :xt/id :slava}}
           (set (xt/q tu/*node*
                      '{:find [1 "foo" xt/id]
                        :where [(match :docs [xt/id])]})))))

(deftest testing-unify-with
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id :ivan, :age 15}]
                            [:put :docs {:xt/id :petr, :age 22}]
                            [:put :docs {:xt/id :slava, :age 37}]])]

    (t/is (= #{{:e1 :petr, :e2 :ivan, :e3 :slava}
               {:e1 :ivan, :e2 :petr, :e3 :slava}}
             (set
              (xt/q tu/*node*
                    '(-> (unify
                          (from :docs [{:xt/id e1 :age a1}])
                          (from :docs [{:xt/id e2 :age a2}])
                          (from :docs [{:xt/id e3 :age a3}])
                          (with {a4 (+ a1 a2)})
                          (where (= a4 a3)))
                         (return :e1 :e2 :e3))))))

    (t/is (= #{{:e1 :petr, :e2 :ivan, :e3 :slava}
               {:e1 :ivan, :e2 :petr, :e3 :slava}}
             (set
              (xt/q tu/*node*
                    '(-> (unify
                          (from :docs [{:xt/id e1 :age a1}])
                          (from :docs [{:xt/id e2 :age a2}])
                          (from :docs [{:xt/id e3 :age a3}])
                          (with {a3 (+ a1 a2)}))
                         (return :e1 :e2 :e3))))))))

#_
(deftest test-namespaced-columns-within-match
  (xt/submit-tx tu/*node* [[:put :docs {:xt/id :ivan :age 10}]])
  (t/is (= [{:xt/id :ivan, :age 10}]
           (xt/q tu/*node* '{:find [xt/id age]
                             :where [(match :docs [xt/id])
                                     [xt/id :age age]]})))
  (t/is (= [{:id :ivan, :age 10}]
           (xt/q tu/*node* '{:find [id age]
                             :where [(match :docs {:xt/id id})
                                     [id :age age]]})))
  (t/is (= [{:id :ivan, :age 10}]
           (xt/q tu/*node* '{:find [id age]
                             :where [(match :docs [{:xt/id id}])
                                     [id :age age]]}))))

#_
(deftest test-nested-expressions-581
  (let [_tx (xt/submit-tx tu/*node*
                          '[[:put :docs {:xt/id :ivan, :age 15}]
                            [:put :docs {:xt/id :petr, :age 22, :height 240, :parent 1}]
                            [:put :docs {:xt/id :slava, :age 37, :parent 2}]])]

    (t/is (= #{{:e1 :ivan, :e2 :petr, :e3 :slava}
               {:e1 :petr, :e2 :ivan, :e3 :slava}}
             (set (xt/q tu/*node*
                        '{:find [e1 e2 e3]
                          :where [(match :docs {:xt/id e1})
                                  (match :docs {:xt/id e2})
                                  (match :docs {:xt/id e3})
                                  [e1 :age a1]
                                  [e2 :age a2]
                                  [e3 :age a3]
                                  [(= (+ a1 a2) a3)]]}))))

    (t/is (= [{:a1 15, :a2 22, :a3 37, :sum-ages 74, :inc-sum-ages 75}]
             (xt/q tu/*node*
                   '{:find [a1 a2 a3 sum-ages inc-sum-ages]
                     :where [(match :docs {:xt/id :ivan})
                             (match :docs {:xt/id :petr})
                             (match :docs {:xt/id :slava})
                             [:ivan :age a1]
                             [:petr :age a2]
                             [:slava :age a3]
                             [(+ (+ a1 a2) a3) sum-ages]
                             [(+ a1 (+ a2 a3 1)) inc-sum-ages]]})))

    (t/testing "unifies results of two calls"
      (t/is (= [{:a1 15, :a2 22, :a3 37, :sum-ages 74}]
               (xt/q tu/*node*
                     '{:find [a1 a2 a3 sum-ages]
                       :where [(match :docs {:xt/id :ivan})
                               (match :docs {:xt/id :petr})
                               (match :docs {:xt/id :slava})
                               [:ivan :age a1]
                               [:petr :age a2]
                               [:slava :age a3]
                               [(+ (+ a1 a2) a3) sum-ages]
                               [(+ a1 (+ a2 a3)) sum-ages]]})))

      (t/is (= []
               (xt/q tu/*node*
                     '{:find [a1 a2 a3 sum-ages]
                       :where [(match :docs {:xt/id :ivan})
                               (match :docs {:xt/id :petr})
                               (match :docs {:xt/id :slava})
                               [:ivan :age a1]
                               [:petr :age a2]
                               [:slava :age a3]
                               [(+ (+ a1 a2) a3) sum-ages]
                               [(+ a1 (+ a2 a3 1)) sum-ages]]}))))))

#_
(deftest test-union-join
  (let [_tx (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :ivan, :age 20, :role :developer}]
                                      [:put :docs {:xt/id :oleg, :age 30, :role :manager}]
                                      [:put :docs {:xt/id :petr, :age 35, :role :qa}]
                                      [:put :docs {:xt/id :sergei, :age 35, :role :manager}]])]

    (letfn [(q [query]
              (xt/q tu/*node* query))]
      (t/is (= [{:e :ivan}]
               (q '{:find [e]
                    :where [(match :docs {:xt/id e})
                            (union-join [e]
                                        (and (match :docs {:xt/id e})
                                             [e :role :developer])
                                        (and (match :docs {:xt/id e})
                                             [e :age 30]))
                            (union-join [e]
                                        (and (match :docs {:xt/id e})
                                             [e :xt/id :petr])
                                        (and (match :docs {:xt/id e})
                                             [e :xt/id :ivan]))]})))

      (t/is (= [{:e :petr}, {:e :oleg}]
               (q '{:find [e]
                    :where [(match :docs {:xt/id :sergei})
                            [:sergei :age age]
                            [:sergei :role role]
                            (union-join [e age role]
                                        (and (match :docs {:xt/id e})
                                             [e :age age])
                                        (and (match :docs {:xt/id e})
                                             [e :role role]))
                            [(<> e :sergei)]]})))

      (t/testing "functions within union-join"
        (t/is (= [{:age 35, :older-age 45}]
                 (q '{:find [age older-age]
                      :where [(match :docs {:xt/id :sergei})
                              [:sergei :age age]
                              (union-join [age older-age]
                                          [(+ age 10) older-age])]})))))))

#_
(deftest test-union-join-with-match-syntax-693
  (let [_tx (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :ivan, :age 20, :role :developer}]
                                      [:put :docs {:xt/id :oleg, :age 30, :role :manager}]
                                      [:put :docs {:xt/id :petr, :age 35, :role :qa}]
                                      [:put :docs {:xt/id :sergei, :age 35, :role :manager}]])]
    (t/is (= [{:e :ivan}]
             (xt/q tu/*node* '{:find [e]
                               :where [(union-join [e]
                                                   (and (match :docs {:xt/id e})
                                                        [e :role :developer])
                                                   (and (match :docs {:xt/id e})
                                                        [e :age 30]))
                                       (union-join [e]
                                                   (and (match :docs {:xt/id e})
                                                        [e :xt/id :petr])
                                                   (and (match :docs {:xt/id e})
                                                        [e :xt/id :ivan]))]})))))

#_
(deftest test-union-join-with-subquery-638
  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :ivan, :age 20, :role :developer}]
                            [:put :docs {:xt/id :oleg, :age 30, :role :manager}]
                            [:put :docs {:xt/id :petr, :age 35, :role :qa}]
                            [:put :docs {:xt/id :sergei, :age 35, :role :manager}]])
  (t/is (= [{:e :oleg}]
           (xt/q tu/*node* '{:find [e]
                             :where [(union-join [e]
                                                 (q {:find [e]
                                                     :where [(match :docs {:xt/id e})
                                                             [e :age 30]]}))]}))))


(deftest test-join-clause
  (xt/submit-tx tu/*node* bond/tx-ops)

  (t/is (= [{:bond-name "Roger Moore", :film-name "A View to a Kill"}
            {:bond-name "Roger Moore", :film-name "For Your Eyes Only"}
            {:bond-name "Roger Moore", :film-name "Live and Let Die"}
            {:bond-name "Roger Moore", :film-name "Moonraker"}
            {:bond-name "Roger Moore", :film-name "Octopussy"}
            {:bond-name "Roger Moore", :film-name "The Man with the Golden Gun"}
            {:bond-name "Roger Moore", :film-name "The Spy Who Loved Me"}]
           (xt/q tu/*node*
                 '(-> (unify (join (-> (unify (from :film [{:film/bond bond}])
                                              (from :person [{:xt/id bond, :person/name bond-name}]))
                                       (aggregate :bond-name :bond {:film-count (count bond)})
                                       (order-by [film-count {:dir :desc}] bond-name)
                                       (limit 1))
                                   [{:bond bond-with-most-films
                                     :bond-name bond-name}])
                             (from :film [{:film/bond bond-with-most-films
                                           :film/name film-name}]))
                      (order-by film-name)
                      (return :bond-name :film-name))))
        "films made by the Bond with the most films")

  #_(t/testing "(contrived) correlated sub-query"
    (xt/submit-tx tu/*node* '[[:put :a {:xt/id :a1, :a 1}]
                              [:put :a {:xt/id :a2, :a 2}]
                              [:put :b {:xt/id :b2, :b 2}]
                              [:put :b {:xt/id :b3, :b 3}]])

    (t/is (= [{:aid :a2, :bid :b2}]
             (xt/q tu/*node*
                   '{:find [aid bid]
                     :where [(match :a {:xt/id aid, :a a})
                             (q {:find [bid]
                                 :in [a]
                                 :where [(match :b {:xt/id bid, :b a})]})]})))))

#_
(t/deftest test-explicit-unwind-574
  (xt/submit-tx tu/*node* bond/tx-ops)

  (t/is (= [{:brand "Aston Martin", :model "DB10"}
            {:brand "Aston Martin", :model "DB5"}
            {:brand "Jaguar", :model "C-X75"}
            {:brand "Jaguar", :model "XJ8"}
            {:brand "Land Rover", :model "Discovery Sport"}
            {:brand "Land Rover", :model "Land Rover Defender Bigfoot"}
            {:brand "Land Rover", :model "Range Rover Sport"}
            {:brand "Mercedes Benz", :model "S-Class"}
            {:brand "Rolls-Royce", :model "Silver Wraith"}]

           (xt/q tu/*node*
                 ['{:find [brand model]
                    :in [film]
                    :where [(match :film {:xt/id film, :film/vehicles [vehicle ...]})
                            (match :vehicle {:xt/id vehicle, :vehicle/brand brand, :vehicle/model model})]
                    :order-by [[brand] [model]]}
                  :spectre]))))

#_
(t/deftest bug-non-string-table-names-599
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 1000}})]
    (letfn [(submit-ops! [ids]
              (last (for [tx-ops (->> (for [id ids]
                                        [:put :t1 {:xt/id id,
                                                   :data (str "data" id)
                                                   }])
                                      (partition-all 20))]
                      (xt/submit-tx node tx-ops))))

            (count-table [_tx]
              (-> (xt/q node '{:find [(count id)]
                               :keys [id-count]
                               :where [(match :t1 {:xt/id id})]})
                  (first)
                  (:id-count)))]

      (let [tx (submit-ops! (range 80))]
        (t/is (= 80 (count-table tx))))

      (let [tx (submit-ops! (range 80 160))]
        (t/is (= 160 (count-table tx)))))))

#_
(t/deftest bug-dont-throw-on-non-existing-column-597
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 1000}})]
    (letfn [(submit-ops! [ids]
              (last (for [tx-ops (->> (for [id ids]
                                        [:put :t1 {:xt/id id,
                                                   :data (str "data" id)}])
                                      (partition-all 20))]
                      (xt/submit-tx node tx-ops))))]

      (xt/submit-tx node '[[:put :docs {:xt/id 0 :foo :bar}]])
      (submit-ops! (range 1010))

      (t/is (= 1010 (-> (xt/q node '{:find [(count id)]
                                     :keys [id-count]
                                     :where [(match :t1 {:xt/id id})]})
                        (first)
                        (:id-count))))

      (t/is (= [{:xt/id 0}]
               (xt/q node '{:find [xt/id]
                            :where [(match :docs [xt/id some-attr])]}))))))

#_
(t/deftest add-better-metadata-support-for-keywords
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 1000}})]
    (letfn [(submit-ops! [ids]
              (last (for [tx-ops (->> (for [id ids]
                                        [:put :t1 {:xt/id id,
                                                   :data (str "data" id)}])
                                      (partition-all 20))]
                      (xt/submit-tx node tx-ops))))]
      (let [_tx1 (xt/submit-tx node '[[:put :docs {:xt/id :some-doc}]])
            ;; going over the chunk boundary
            tx2 (submit-ops! (range 200))]
        (t/is (= [{:xt/id :some-doc}]
                 (xt/q node '{:find [xt/id]
                              :where [(match :docs [xt/id])
                                      [xt/id :xt/id :some-doc]]})))))))

#_
(deftest test-subquery-unification
  (let [tx (xt/submit-tx tu/*node* '[[:put :a {:xt/id :a1, :a 2 :b 1}]
                                     [:put :a {:xt/id :a2, :a 2 :b 3}]
                                     [:put :a {:xt/id :a3, :a 2 :b 0}]])]

    (t/testing "variables returned from subqueries that must be run as an apply are unified"

      (t/testing "subquery"
        (t/is (= [{:aid :a2 :a 2 :b 3}]
                 (xt/q tu/*node*
                       '{:find [aid a b]
                         :where [(match :a [{:xt/id aid} a b])
                                 (q {:find [b]
                                     :in [a]
                                     :where [[(+ a 1) b]]})]}))
              "b is unified"))

      (t/testing "union-join"
        (t/is (= [{:aid :a2 :a 2 :b 3}]
                 (xt/q tu/*node*
                       '{:find [aid a b]
                         :where [(match :a [{:xt/id aid} a b])
                                 (union-join [a b]
                                             [(+ a 1) b])]}))
              "b is unified")))))

#_
(deftest test-basic-rules
  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :ivan :name "Ivan" :last-name "Ivanov" :age 21}]
                            [:put :docs {:xt/id :petr :name "Petr" :last-name "Petrov" :age 18}]
                            [:put :docs {:xt/id :georgy :name "Georgy" :last-name "George" :age 17}]])
  (letfn [(q [query & args]
            (apply xt/q tu/*node* query args))]

    (t/testing "without rule"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         [(>= age 21)]]}))))

    (t/testing "empty rules"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         [(>= age 21)]]
                                 :rules []}))))

    (t/testing "rule using required bound args"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         (over-twenty-one? age)]
                                 :rules [[(over-twenty-one? age)
                                          [(>= age 21)]]]}))))

    (t/testing "rule using required bound args (different arg names)"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         (over-twenty-one? age)]
                                 :rules [[(over-twenty-one? age-other)
                                          [(>= age-other 21)]]]}))))

    (t/testing "rules directly on arguments"
      (t/is (= [{:age 21}] (q ['{:find [age]
                                 :where [(over-twenty-one? age)]
                                 :in [age]
                                 :rules [[(over-twenty-one? age)
                                          [(>= age 21)]]]}
                               21])))

      (t/is (= [] (q ['{:find [age]
                        :where [(over-twenty-one? age)]
                        :in [age]
                        :rules [[(over-twenty-one? age)
                                 [(>= age 21)]]]}
                      20]))))

    (t/testing "testing rule with multiple args"
      (t/is (= #{{:i :petr, :age 18, :u :ivan}
                 {:i :georgy, :age 17, :u :ivan}
                 {:i :georgy, :age 17, :u :petr}}
               (set (q '{:find [i age u]
                         :where [(older-users age u)
                                 (match :docs {:xt/id i})
                                 [i :age age]]
                         :rules [[(older-users age u)
                                  (match :docs {:xt/id u})
                                  [u :age age2]
                                  [(> age2 age)]]]})))))

    (t/testing "testing rule with multiple args (different arg names in rule)"
      (t/is (= #{{:i :petr, :age 18, :u :ivan}
                 {:i :georgy, :age 17, :u :ivan}
                 {:i :georgy, :age 17, :u :petr}}
               (set (q '{:find [i age u]
                         :where [(older-users age u)
                                 (match :docs {:xt/id i})
                                 [i :age age]]
                         :rules [[(older-users age-other u-other)
                                  (match :docs {:xt/id u-other})
                                  [u-other :age age2]
                                  [(> age2 age-other)]]]})))))


    (t/testing "nested rules"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         (over-twenty-one? age)]
                                 :rules [[(over-twenty-one? x)
                                          (over-twenty-one-internal? x)]
                                         [(over-twenty-one-internal? y)
                                          [(>= y 21)]]]}))))

    (t/testing "nested rules bound (same arg names)"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         (over-twenty-one? age)]
                                 :rules [[(over-twenty-one? age)
                                          (over-twenty-one-internal? age)]
                                         [(over-twenty-one-internal? age)
                                          [(>= age 21)]]]}))))

    (t/is (= [{:i :ivan}] (q '{:find [i]
                               :where [(match :docs {:xt/id i})
                                       [i :age age]
                                       (over-twenty-one? age)]
                               :rules [[(over-twenty-one? x)
                                        (over-twenty-one-internal? x)]
                                       [(over-twenty-one-internal? y)
                                        [(>= y 21)]]]})))

    (t/testing "rule using literal arguments"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         (over-age? age 21)]
                                 :rules [[(over-age? age required-age)
                                          [(>= age required-age)]]]}))))

    (t/testing "same arg-name different position test (shadowing test)"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(match :docs {:xt/id i})
                                         [i :age age]
                                         (over-age? age 21)]
                                 :rules [[(over-age? other-age age)
                                          [(>= other-age age)]]]}))))

    (t/testing "semi-join in rule"
      (t/is (= [{:i :petr, :age 18}
                {:i :georgy, :age 17}]
               (q '{:find [i age]
                    :where [(match :docs {:xt/id i})
                            [i :age age]
                            (older? age)]
                    :rules [[(older? age)
                             (exists? {:find []
                                       :in [age]
                                       :where [(match :docs {:xt/id i})
                                               [i :age age2]
                                               [(> age2 age)]]})]]}))))


    (t/testing "anti-join in rule"
      (t/is (= [{:i :ivan, :age 21}]
               (q  '{:find [i age]
                     :where [(match :docs {:xt/id i})
                             [i :age age]
                             (not-older? age)]
                     :rules [[(not-older? age)
                              (not-exists? {:find []
                                            :in [age]
                                            :where [(match :docs {:xt/id i})
                                                    [i :age age2]
                                                    [(> age2 age)]]})]]}))))

    (t/testing "subquery in rule"
      (t/is (= #{{:i :petr, :other-age 21}
                 {:i :georgy, :other-age 21}
                 {:i :georgy, :other-age 18}}
               (set (q '{:find [i other-age]
                         :where [(match :docs {:xt/id i})
                                 [i :age age]
                                 (older-ages age other-age)]
                         :rules [[(older-ages age other-age)
                                  (q {:find [other-age]
                                      :in [age]
                                      :where [(match :docs {:xt/id i})
                                              [i :age other-age]
                                              [(> other-age age)]]})]]})))))

    (t/testing "subquery in rule with aggregates, expressions and order-by"
      (t/is [{:i :ivan, :max-older-age nil, :max-older-age-times2 nil}
             {:i :petr, :max-older-age 21, :max-older-age-times2 42}
             {:i :georgy, :max-older-age 21, :max-older-age-times2 42}]
            (q '{:find [i max-older-age max-older-age-times2]
                 :where [(match :docs {:xt/id i})
                         [i :age age]
                         (older-ages age max-older-age max-older-age-times2)]
                 :rules [[(older-ages age max-older-age max-older-age2)
                          (q {:find [(max older-age) (max (* older-age 2))]
                              :keys [max-older-age max-older-age2]
                              :in [age]
                              :where [(match :docs {:xt/id i})
                                      [i :age older-age]
                                      [(> older-age age)]]
                              :order-by [[(max older-age) :desc]]})]]})))

    (t/testing "rule using multiple branches"
      (t/is (= [{:i :ivan}] (q '{:find [i]
                                 :where [(is-ivan-or-bob? i)]
                                 :rules [[(is-ivan-or-bob? i)
                                          (match :docs {:xt/id i})
                                          [i :name "Bob"]]
                                         [(is-ivan-or-bob? i)
                                          (match :docs {:xt/id i})
                                          [i :name "Ivan"]
                                          [i :last-name "Ivanov"]]]})))

      (t/is (= [{:name "Petr"}] (q '{:find [name]
                                     :where [(match :docs {:xt/id i})
                                             [i :name name]
                                             (not-exists? {:find [i]
                                                           :where [(is-ivan-or-georgy? i)]})]
                                     :rules [[(is-ivan-or-georgy? i)
                                              (match :docs {:xt/id i})
                                              [i :name "Ivan"]]
                                             [(is-ivan-or-georgy? i)
                                              (match :docs {:xt/id i})
                                              [i :name "Georgy"]]]})))

      (t/is (= [{:i :ivan}
                {:i :petr}]
               (q '{:find [i]
                    :where [(is-ivan-or-petr? i)]
                    :rules [[(is-ivan-or-petr? i)
                             (match :docs {:xt/id i})
                             [i :name "Ivan"]]
                            [(is-ivan-or-petr? i)
                             (match :docs {:xt/id i})
                             [i :name "Petr"]]]}))))

    (t/testing "union-join with rules"
      (t/is (= [{:i :ivan}]
               (q '{:find [i]
                    :where [(union-join [i]
                                        (is-ivan-or-bob? i))]
                    :rules [[(is-ivan-or-bob? i)
                             (match :docs {:xt/id i})
                             [i :name "Bob"]]
                            [(is-ivan-or-bob? i)
                             (match :docs {:xt/id i})
                             [i :name "Ivan"]
                             [i :last-name "Ivanov"]]]}))))


    (t/testing "subquery with rule"
      (t/is (= [{:i :ivan}]
               (q '{:find [i]
                    :where [(q {:find [i]
                                :where [(is-ivan-or-bob? i)]})]
                    :rules [[(is-ivan-or-bob? i)
                             (match :docs {:xt/id i})
                             [i :name "Bob"]]
                            [(is-ivan-or-bob? i)
                             (match :docs {:xt/id i})
                             [i :name "Ivan"]
                             [i :last-name "Ivanov"]]]})))))

  (t/is (thrown-with-msg?
         IllegalArgumentException
         #":unknown-rule"
         (xt/q tu/*node* '{:find [i]
                           :where [(match :docs {:xt/id i})
                                   [i :age age]
                                   (over-twenty-one? age)]})))

  (t/is (thrown-with-msg?
         IllegalArgumentException
         #":rule-wrong-arity"
         (xt/q tu/*node* '{:find [i]
                           :where [(match :docs {:xt/id i})
                                   [i :age age]
                                   (over-twenty-one? i age)]
                           :rules [[(over-twenty-one? x)
                                    [(>= x 21)]]]})))
  (t/is (thrown-with-msg?
         IllegalArgumentException
         #":rule-definitions-require-unique-arity"
         (xt/q tu/*node* '{:find [i]
                           :where [(match :docs {:xt/id i})
                                   [i :age age]
                                   (is-ivan-or-petr? i name)]
                           :rules [[(is-ivan-or-petr? i name)
                                    (match :docs {:xt/id i})
                                    [i :name "Ivan"]]
                                   [(is-ivan-or-petr? i)
                                    (match :docs {:xt/id i})
                                    [i :name "Petr"]]]}))))


(t/deftest test-temporal-opts
  (letfn [(q [query tx current-time]
            (xt/q tu/*node*
                  query
                  {:basis {:tx tx, :current-time (util/->instant current-time)}}))]

    ;; Matthew 2015+

    ;; tx0
    ;; 2018/2019: Matthew, Mark
    ;; 2021+: Matthew, Luke

    ;; tx1
    ;; 2016-2018: Matthew, John
    ;; 2018-2020: Matthew, Mark, John
    ;; 2020: Matthew
    ;; 2021-2022: Matthew, Luke
    ;; 2023: Matthew, Mark (again)
    ;; 2024+: Matthew

    (let [tx0 (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :matthew} {:for-valid-time [:in #inst "2015"]}]
                                        [:put :docs {:xt/id :mark} {:for-valid-time [:in  #inst "2018"  #inst "2020"]}]
                                        [:put :docs {:xt/id :luke} {:for-valid-time [:in #inst "2021"]}]])

          tx1 (xt/submit-tx tu/*node* '[[:delete :docs :luke {:for-valid-time [:in #inst "2022"]}]
                                        [:put :docs {:xt/id :mark} {:for-valid-time [:in #inst "2023" #inst "2024"]}]
                                        [:put :docs {:xt/id :john} {:for-valid-time [:in #inst "2016" #inst "2020"]}]])]

      (t/is (= #{{:id :matthew}, {:id :mark}}
               (set (q '(from :docs [{:xt/id id}]), tx1, #inst "2023"))))

      (t/is (= #{{:id :matthew}, {:id :luke}}
               (set (q '(from :docs [{:xt/id id}]), tx1, #inst "2021")))
            "back in app-time")

      (t/is (= #{{:id :matthew}, {:id :luke}}
               (set (q '(from :docs [{:xt/id id}]), tx0, #inst "2023")))
            "back in system-time")

      (t/is (= #{{:id :matthew, :app-from (util/->zdt #inst "2015"), :app-to nil}
                 {:id :mark, :app-from (util/->zdt #inst "2018"), :app-to (util/->zdt #inst "2020")}
                 {:id :luke, :app-from (util/->zdt #inst "2021"), :app-to (util/->zdt #inst "2022")}
                 {:id :mark, :app-from (util/->zdt #inst "2023"), :app-to (util/->zdt #inst "2024")}
                 {:id :john, :app-from (util/->zdt #inst "2016"), :app-to (util/->zdt #inst "2020")}}
               (set (q '(from :docs {:bind [{:xt/id id} {:xt/valid-from app-from
                                                         :xt/valid-to app-to}]
                                     :for-valid-time :all-time})
                       tx1, nil)))
            "entity history, all time")

      (t/is (= #{{:id :matthew, :app-from (util/->zdt #inst "2015"), :app-to nil}
                 {:id :luke, :app-from (util/->zdt #inst "2021"), :app-to (util/->zdt #inst "2022")}}
               (set (q '(from :docs {:bind [{:xt/id id} {:xt/valid-from app-from
                                                         :xt/valid-to app-to}]
                                     :for-valid-time (in #inst "2021", #inst "2023")})
                       tx1, nil)))
            "entity history, range")

      (t/is (= #{{:id :matthew}}
               (set (q '(unify (from :docs {:bind [{:xt/id id}]
                                            :for-valid-time (at #inst "2018")})
                               (from :docs {:bind [{:xt/id id}]
                                            :for-valid-time (at #inst "2024")})),
                       tx1, nil)))
            "cross-time join - who was here in both 2018 and 2023?")

      (t/is (= #{{:vt-from (util/->zdt #inst "2021")
                  :vt-to (util/->zdt #inst "2022")
                  :tt-from (util/->zdt #inst "2020")
                  :tt-to nil}
                 {:vt-from (util/->zdt #inst "2022")
                  :vt-to nil
                  :tt-from (util/->zdt #inst "2020")
                  :tt-to (util/->zdt  #inst "2020-01-02")}}
               (set (q '(from :docs {:bind [{:xt/id :luke
                                             :xt/valid-from vt-from
                                             :xt/valid-to vt-to
                                             :xt/system-from tt-from
                                             :xt/system-to tt-to}]
                                     :for-valid-time :all-time
                                     :for-system-time :all-time})
                       tx1 nil)))

            "for all sys time"))))

(t/deftest test-for-valid-time-with-current-time-2493
  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :matthew} {:for-valid-time [:in nil #inst "2040"]}]])
  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :matthew} {:for-valid-time [:in #inst "2022" #inst "2030"]}]])
  (t/is (= #{{:id :matthew,
              :vt-from #time/zoned-date-time "2030-01-01T00:00Z[UTC]",
              :vt-to #time/zoned-date-time "2040-01-01T00:00Z[UTC]"}
             {:id :matthew,
              :vt-from #time/zoned-date-time "2022-01-01T00:00Z[UTC]",
              :vt-to #time/zoned-date-time "2030-01-01T00:00Z[UTC]"}}
           (set (xt/q tu/*node*
                      '(from :docs {:bind [{:xt/id id
                                            :xt/valid-from vt-from
                                            :xt/valid-to vt-to}]
                                    :for-valid-time (in nil #inst "2040")})
                      {:basis {:current-time (util/->instant #inst "2023")}})))))

(t/deftest test-temporal-opts-from-and-to
  (letfn [(q [query tx current-time]
            (xt/q tu/*node* query
                  {:basis {:tx tx, :current-time (util/->instant current-time)}}))]

    ;; tx0
    ;; 2015 - eof : Matthew
    ;; now - 2050 : Mark

    ;; tx1
    ;; now - 2040 : Matthew

    (let [tx0 (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :matthew} {:for-valid-time [:from #inst "2015"]}]
                                        [:put :docs {:xt/id :mark} {:for-valid-time [:to #inst "2050"]}]])
          tx1 (xt/submit-tx tu/*node* '[[:put :docs {:xt/id :matthew} {:for-valid-time [:to #inst "2040"]}]])]
      (t/is (= #{{:id :matthew,
                  :vt-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :vt-to nil}
                 {:id :mark,
                  :vt-from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                  :vt-to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"}}
               (set (q '(from :docs [{:xt/id id
                                      :xt/valid-from vt-from
                                      :xt/valid-to vt-to}]),
                       tx0, #inst "2023"))))

      (t/is (= [{:id :matthew,
                 :vt-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                 :vt-to nil}]
               (q '(from :docs {:bind [{:xt/id id
                                        :xt/valid-from vt-from
                                        :xt/valid-to vt-to}]
                                :for-valid-time (from #inst "2051")}),
                  tx0, #inst "2023")))

      (t/is (= [{:id :mark,
                 :vt-from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                 :vt-to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"}
                {:id :matthew,
                 :vt-from #time/zoned-date-time "2020-01-02T00:00Z[UTC]",
                 :vt-to #time/zoned-date-time "2040-01-01T00:00Z[UTC]"}]
               (q '(from :docs {:bind [{:xt/id id
                                        :xt/valid-from vt-from
                                        :xt/valid-to vt-to}]
                                :for-valid-time (to #inst "2040")}),
                  tx1, #inst "2023"))))))

(deftest test-snodgrass-99-tutorial
  (letfn [(q [q tx current-time]
            (xt/q tu/*node* q
                  {:basis {:tx tx, :current-time (util/->instant current-time)}}))
          (q-with-args [q args tx current-time]
            (xt/q tu/*node* q
                  {:args args :basis {:tx tx, :current-time (util/->instant current-time)}}))]

    (let [tx0 (xt/submit-tx tu/*node*
                            '[[:put :docs {:xt/id 1 :customer-number 145 :property-number 7797}
                               {:for-valid-time [:in #inst "1998-01-10"]}]]
                            {:system-time #inst "1998-01-10"})

          tx1 (xt/submit-tx tu/*node*
                            '[[:put :docs {:xt/id 1 :customer-number 827 :property-number 7797}
                               {:for-valid-time [:in  #inst "1998-01-15"] }]]
                            {:system-time #inst "1998-01-15"})

          _tx2 (xt/submit-tx tu/*node*
                             '[[:delete :docs 1 {:for-valid-time [:in #inst "1998-01-20"]}]]
                             {:system-time #inst "1998-01-20"})

          _tx3 (xt/submit-tx tu/*node*
                             '[[:put :docs {:xt/id 1 :customer-number 145 :property-number 7797}
                                {:for-valid-time [:in #inst "1998-01-03" #inst "1998-01-10"]}]]
                             {:system-time #inst "1998-01-23"})

          _tx4 (xt/submit-tx tu/*node*
                             '[[:delete :docs 1 {:for-valid-time [:in #inst "1998-01-03" #inst "1998-01-05"]}]]
                             {:system-time #inst "1998-01-26"})

          tx5 (xt/submit-tx tu/*node*
                            '[[:put :docs {:xt/id 1 :customer-number 145 :property-number 7797}
                               {:for-valid-time [:in #inst "1998-01-05" #inst "1998-01-12"]}]
                              [:put :docs {:xt/id 1 :customer-number 827 :property-number 7797}
                               {:for-valid-time [:in #inst "1998-01-12" #inst "1998-01-20"]}]]
                            {:system-time #inst "1998-01-28"})

          tx6 (xt/submit-tx tu/*node*
                            [[:put-fn :delete-1-week-records,
                              '(fn delete-1-weeks-records []
                                 (->> (q
                                       '{:find [id app-from app-to]
                                         :where [(match :docs {:xt/id id
                                                               :xt/valid-from app-from
                                                               :xt/valid-to app-to}
                                                        {:for-valid-time :all-time})
                                                 [(= (- #inst "1970-01-08" #inst "1970-01-01")
                                                     (- app-to app-from))]]}
                                       ;;TODO tx-fn needs to support XTQL
                                       #_'(-> (from :docs {:bind [{:xt/id id
                                                                   :xt/valid-from app-from
                                                                   :xt/valid-to app-to}]
                                                           :for-valid-time :all-time})
                                              (where (= (- #inst "1970-01-08" #inst "1970-01-01")
                                                        (- app-to app-from)))))
                                      (map (fn [{:keys [id app-from app-to]}]
                                             [:delete :docs id {:for-valid-time [:in app-from app-to]}]))))]
                             [:call :delete-1-week-records]]
                            {:system-time #inst "1998-01-30"})

          tx7 (xt/submit-tx tu/*node*
                            '[[:put :docs {:xt/id 2 :customer-number 827 :property-number 3621}
                               {:for-valid-time [:in #inst "1998-01-15"]}]]
                            {:system-time #inst "1998-01-31"})]

      (t/is (= [{:cust 145 :app-from (util/->zdt #inst "1998-01-10")}]
               (q '(from :docs {:bind [{:customer-number cust, :xt/valid-from app-from}]
                                :for-valid-time :all-time})
                  tx0, nil))
            "as-of 14 Jan")

      (t/is (= #{{:cust 145, :app-from (util/->zdt #inst "1998-01-10")}
                 {:cust 827, :app-from (util/->zdt #inst "1998-01-15")}}
               (set (q '(from :docs {:bind [{:customer-number cust, :xt/valid-from app-from}]
                                     :for-valid-time :all-time})
                       tx1, nil)))
            "as-of 18 Jan")

      (t/is (= #{{:cust 145, :app-from (util/->zdt #inst "1998-01-05")}
                 {:cust 827, :app-from (util/->zdt #inst "1998-01-12")}}
               (set (q '(-> (from :docs {:bind [{:customer-number cust,
                                                 :xt/valid-from app-from}]
                                         :for-valid-time :all-time})
                            (order-by [app-from {:dir :asc}]))
                       tx5, nil)))
            "as-of 29 Jan")

      (t/is (= [{:cust 827, :app-from (util/->zdt #inst "1998-01-12"), :app-to (util/->zdt #inst "1998-01-20")}]
               (q '(-> (from :docs {:bind [{:customer-number cust,
                                            :xt/valid-from app-from
                                            :xt/valid-to app-to}]
                                    :for-valid-time :all-time})
                       (order-by [app-from {:dir :asc}]))
                  tx6, nil))
            "'as best known' (as-of 30 Jan)")

      (t/is (= [{:prop 3621, :vt-begin (util/->zdt #inst "1998-01-15"), :vt-to (util/->zdt #inst "1998-01-20")}]
               (q-with-args
                '(-> (unify (from :docs {:bind [{:property-number $prop
                                                 :customer-number cust
                                                 :xt/valid-time app-time
                                                 :xt/valid-from app-from
                                                 :xt/valid-to app-to}]
                                         :for-valid-time :all-time})

                            (from :docs {:bind [{:property-number prop
                                                 :customer-number cust
                                                 :xt/valid-time app-time-2
                                                 :xt/valid-from app-from2
                                                 :xt/valid-to app-to2}]
                                         :for-valid-time :all-time}))

                     (where (<> prop $prop)
                            (overlaps? app-time app-time-2))
                     (order-by [app-from {:dir :asc}])
                     (return :prop
                             {:vt-begin (greatest app-from app-from2)}
                             {:vt-to (least app-to app-to2)}))
                {:prop 7797}
                tx7, nil))
            "Case 2: Valid-time sequenced and transaction-time current")

      (t/is (= [{:prop 3621,
                 :vt-begin (util/->zdt #inst "1998-01-15"),
                 :vt-to (util/->zdt #inst "1998-01-20"),
                 :recorded-from (util/->zdt #inst "1998-01-31"),
                 :recorded-stop nil}]
               (q-with-args
                '(-> (unify (from :docs {:bind [{:property-number $prop
                                                 :customer-number cust
                                                 :xt/valid-time app-time
                                                 :xt/system-time system-time
                                                 :xt/valid-from app-from
                                                 :xt/valid-to app-to
                                                 :xt/system-from sys-from
                                                 :xt/system-to sys-to}]
                                         :for-valid-time :all-time
                                         :for-system-time :all-time})

                            (from :docs {:bind [{:customer-number cust
                                                 :property-number prop
                                                 :xt/valid-time app-time-2
                                                 :xt/system-time system-time-2
                                                 :xt/valid-from app-from2
                                                 :xt/valid-to app-to2
                                                 :xt/system-from sys-from2
                                                 :xt/system-to sys-to2}]

                                         :for-valid-time :all-time
                                         :for-system-time :all-time}))

                     (where (<> prop $prop)
                            (overlaps? app-time app-time-2)
                            (overlaps? system-time system-time-2))
                     (order-by [app-from {:dir :asc}])
                     (return :prop {:vt-begin (greatest app-from app-from2)
                                    :vt-to (least app-to app-to2)
                                    :recorded-from (greatest sys-from sys-from2)
                                    :recorded-stop (least sys-to sys-to2)}))
                {:prop 7797}
                tx7, nil))
            "Case 5: Application-time sequenced and system-time sequenced")

      (t/is (= [{:prop 3621,
                 :vt-begin (util/->zdt #inst "1998-01-15"),
                 :vt-to (util/->zdt #inst "1998-01-20"),
                 :recorded-from (util/->zdt #inst "1998-01-31")}]
               (q-with-args
                '(-> (unify (from :docs {:bind [{:property-number $prop
                                                 :customer-number cust
                                                 :xt/valid-time app-time
                                                 :xt/system-time system-time
                                                 :xt/valid-from app-from
                                                 :xt/valid-to app-to
                                                 :xt/system-from sys-from
                                                 :xt/system-to sys-to}]
                                         :for-valid-time :all-time
                                         :for-system-time :all-time})

                            (from :docs {:bind [{:customer-number cust
                                                 :property-number prop
                                                 :xt/valid-time app-time-2
                                                 :xt/system-time system-time-2
                                                 :xt/valid-from app-from2
                                                 :xt/valid-to app-to2
                                                 :xt/system-from sys-from2
                                                 :xt/system-to sys-to2}]}))

                     (where (<> prop $prop)
                            (overlaps? app-time app-time-2)
                            (contains? system-time sys-from2))
                     (order-by [app-from {:dir :asc}])
                     (return :prop
                             {:vt-begin (greatest app-from app-from2)
                              :vt-to (least app-to app-to2)
                              :recorded-from sys-from2}))
                {:prop 7797}
                tx7, nil))
            "Case 8: Application-time sequenced and system-time nonsequenced"))))

(deftest scalar-sub-queries-test
  (xt/submit-tx tu/*node* [[:put :customer {:xt/id 0, :firstname "bob", :lastname "smith"}]
                           [:put :customer {:xt/id 1, :firstname "alice" :lastname "carrol"}]
                           [:put :order {:xt/id 0, :customer 0, :items [{:sku "eggs", :qty 1}]}]
                           [:put :order {:xt/id 1, :customer 0, :items [{:sku "cheese", :qty 3}]}]
                           [:put :order {:xt/id 2, :customer 1, :items [{:sku "bread", :qty 1} {:sku "eggs", :qty 2}]}]])

  ;;TODO nested subqeries
  (t/are [q result] (= (into #{} result) (set (xt/q tu/*node* q)))

    '(unify (with {n-customers (q (-> (from :customer {:bind [{:xt/id id}]})
                                      (aggregate {:id-count (count id)})))}))
    [{:n-customers 2}]

    '(unify (with {n-customers (+ 1 (q (-> (from :customer {:bind [{:xt/id id}]})
                                           (aggregate {:id-count (count id)}))))}))
    [{:n-customers 3}]

    '(-> (from :customer [{:xt/id customer}])
         (with {:n-orders
                (q (-> (from :order [{:customer $customer, :xt/id order}])
                       (aggregate {:count (count order)}))
                   {:args [customer]})}))

    [{:customer 0, :n-orders 2}
     {:customer 1, :n-orders 1}]


    ;;TODO needs spread/unwind op
    #_#_
    '{:find [customer, n-orders, n-qty]
      :where [(match :customer {:xt/id customer})
              [(q {:find [(count order)]
                   :in [customer]
                   :where [(match :order {:customer customer, :xt/id order})]})
               n-orders]
              [(q {:find [(sum qty2)]
                   :in [customer]
                   :where [(match :order {:customer customer, :xt/id order, :items [item ...]})
                           [(. item :qty) qty2]]})
               n-qty]]}
    [{:customer 0, :n-orders 2, :n-qty 4}
     {:customer 1, :n-orders 1, :n-qty 3}]

    #_#_ '{:find [n-orders, n-qty]
           :where [[(q {:find [(count order)]
                        :where [(match :order {:xt/id order})]})
                    n-orders]
                   [(q {:find [(sum qty2)]
                        :where [(match :order {:xt/id order, :items [item ...]})
                                [(. item :qty) qty2]]})
                    n-qty]]}
    [{:n-orders 3, :n-qty 7}]


    '(-> (from :order {:bind [{:xt/id order :customer customer}]})
         (with {:firstname (q (from :customer {:bind [{:xt/id $customer, :firstname firstname}]})
                              {:args [customer]})})
         (without :customer))

    [{:order 0, :firstname "bob"}
     {:order 1, :firstname "bob"}
     {:order 2, :firstname "alice"}]


    '(-> (from :order {:bind [{:xt/id order :customer customer}]})
         (with {:firstname (q (from :customer {:bind [{:xt/id $customer, :firstname firstname2}]})
                              {:args [customer]})})
         (without :customer))

    [{:order 0, :firstname "bob"}
     {:order 1, :firstname "bob"}
     {:order 2, :firstname "alice"}]

    '(-> (from :order {:bind [{:xt/id order, :customer customer}]})
         (with {:fullname (concat
                           (q (from :customer {:bind [{:xt/id $customer, :firstname fn}]})
                              {:args [customer]})
                           " "
                           (q (from :customer {:bind [{:xt/id $customer, :lastname fn}]})
                              {:args [customer]}))})
         (without :customer))

    [{:order 0, :fullname "bob smith"}
     {:order 1, :fullname "bob smith"}
     {:order 2, :fullname "alice carrol"}]

    '(-> (from :order {:bind [{:xt/id order, :customer customer}]})
         (where (= (q (from :customer {:bind [{:xt/id $customer, :firstname fn}]})
                      {:args [customer]})
                   "bob"))
         (without :customer))

    [{:order 0}
     {:order 1}]

    '(-> (unify (from :order {:bind [{:xt/id order, :customer customer}]})
                (where (= (q (from :customer {:bind [{:xt/id $customer, :firstname fn}]})
                             {:args [customer]})
                          "bob")))
         (without :customer))

    [{:order 0}
     {:order 1}]

    '(unify
      (with {n (q (-> (from :customer {:bind [{:xt/id c}]})
                      (aggregate {:c-count (count c)})))}))
    [{:n 2}]


    '(unify
      (with {c (q (-> (from :customer {:bind [{:xt/id c}]})
                      (aggregate {:count (count c)})))
             o (q (-> (from :order {:bind [{:xt/id o}]})
                      (aggregate {:count (count o)})))}))
    [{:c 2, :o 3}]

    '(-> (from :customer {:bind [{:xt/id cid, :firstname "bob"}]})
         (with {c (q (-> (from :customer {:bind [{:xt/id $cid} {:xt/id c}]})
                         (aggregate {:count (count c)}))
                     {:args [cid]})}
               {o (q (-> (from :order {:bind [{:customer $cid} {:xt/id o}]})
                         (aggregate {:count (count o)}))
                     {:args [cid]})})
         (without :cid))

    [{:c 1, :o 2}])

  (t/testing "cardinality violation error"
    (t/is (thrown-with-msg? xtdb.RuntimeException #"cardinality violation"
                            (->> '(unify
                                   (with {first-name (q (from :customer [{:firstname firstname}]))}))
                                 (xt/q tu/*node*)))))

  (t/testing "multiple column error"
    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Scalar subquery must only return a single column"
                            (->> '(unify
                                   (with
                                    {n-customers
                                     (q (from :customer [{:customer customer, :firstname firstname}]))}))
                                 (xt/q tu/*node*))))))

(deftest test-period-predicates

  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id 1} {:for-valid-time [:in #inst "2015" #inst "2020"]}]
                            [:put :xt_cats {:xt/id 2} {:for-valid-time [:in #inst "2016" #inst "2018"]}]])

  (t/is (= [{:xt/id 1, :id2 2,
             :docs_app_time {:from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                             :to #time/zoned-date-time "2020-01-01T00:00Z[UTC]"},
             :xt_cats_app_time {:from #time/zoned-date-time "2016-01-01T00:00Z[UTC]",
                                :to #time/zoned-date-time "2018-01-01T00:00Z[UTC]"}}]
           (xt/q
            tu/*node*
            '(-> (unify (from :docs {:bind [xt/id {:xt/valid-time docs_app_time}]
                                     :for-valid-time :all-time})
                        (from :xt_cats {:bind [{:xt/valid-time xt_cats_app_time :xt/id id2}]
                                        :for-valid-time :all-time}))
                 (where (contains? docs_app_time xt_cats_app_time))))))

  (t/is (= [{:xt/id 1, :id2 2,
             :docs_sys_time {:from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                             :to nil},
             :xt_cats_sys_time {:from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                                :to nil}}]
           (xt/q
            tu/*node*
            '(unify (from :docs {:bind [xt/id {:xt/system-time docs_sys_time}]
                                 :for-valid-time :all-time
                                 :for-system-time :all-time})
                    (from :xt_cats {:bind [{:xt/system-time xt_cats_sys_time :xt/id id2}]
                                    :for-valid-time :all-time
                                    :for-system-time :all-time})
                    (where (equals? docs_sys_time xt_cats_sys_time)))))))


(deftest test-period-constructor
  ;;TODO Needs table/values op
  #_#_(t/is (= [{:p1 {:from #time/zoned-date-time "2018-01-01T00:00Z[UTC]",
                  :to #time/zoned-date-time "2022-01-01T00:00Z[UTC]"}}]
           (xt/q
             tu/*node*
             '{:find [p1],
               :where [[(period #inst "2018" #inst "2022") p1]]})))

  (t/is (thrown-with-msg?
          RuntimeException
          #"From cannot be greater than to when constructing a period"
          (xt/q
            tu/*node*
            '{:find [p1],
              :where [[(period #inst "2022" #inst "2020") p1]]}))))


(deftest test-period-and-temporal-col-projection
  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id 1} {:for-valid-time [:in #inst "2015" #inst "2050"]}]])


  (t/is (= [{:xt/id 1,
             :app_time {:from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                        :to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"},
             :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
             :app-time-to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"}]
           (xt/q
            tu/*node*
            '(from :docs {:bind [xt/id xt/valid-from
                                 {:xt/valid-time app_time
                                  :xt/valid-to app-time-to}]
                          :for-valid-time :all-time})))
        "projecting both period and underlying cols")

  (t/is (= [{:xt/id 1,
             :app_time {:from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                        :to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"},
             :sys_time {:from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                        :to nil}}]
           (xt/q
            tu/*node*
            '(from :docs {:bind [xt/id {:xt/valid-time app_time
                                        :xt/system-time sys_time}]
                          :for-valid-time :all-time
                          :for-system-time :all-time})))
        "projecting both app and system-time periods")

  (t/is (= [#:xt{:valid-time
                 {:from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"}}]
           (xt/q
            tu/*node*
            '(from :docs
                   {:bind [id xt/valid-time]
                    :for-valid-time :all-time})))
        "protecting temporal period in vector syntax")

  (t/is (= [{:xt/id 1
             :id2 1,
             :app_time {:from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                        :to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"},
             :sys_time {:from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                        :to nil}}]
           (xt/q
            tu/*node*
            '(unify (from :docs {:bind [xt/id {:xt/valid-time app_time
                                          :xt/system-time sys_time}]
                                  :for-valid-time :all-time
                                  :for-system-time :all-time})
                    (from :docs {:bind [{:xt/valid-time app_time
                                         :xt/system-time sys_time
                                         :xt/id id2}]
                                 :for-valid-time :all-time
                                 :for-system-time :all-time}))))
        "period unification")

  (xt/submit-tx tu/*node* '[[:put :docs {:xt/id 2}]])

  (t/is (= [{:xt/id 2,
             :time {:from #time/zoned-date-time "2020-01-02T00:00Z[UTC]",
                    :to nil}}]
           (xt/q
            tu/*node*
            '(from :docs
                   {:bind [xt/id {:xt/valid-time time
                                  :xt/system-time time}]
                    :for-valid-time :all-time
                    :for-system-time :all-time})))
        "period unification within match")

  (xt/submit-tx tu/*node* '[[:put :docs
                             {:xt/id 3 :c {:from #inst "2015" :to #inst "2050"}}
                             {:for-valid-time [:in #inst "2015" #inst "2050"]}]])

  (t/is (= [{:xt/id 3,
             :time
             {:from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
              :to #time/zoned-date-time "2050-01-01T00:00Z[UTC]"}}]
           (xt/q
            tu/*node*
            '(from :docs {:bind [xt/id {:xt/valid-time time
                                        :c time}]
                          :for-valid-time :all-time
                          :for-system-time :all-time})))
        "period unification within match with user period column")

  (t/is (= [{:xt/id 1} {:xt/id 3}]
           (xt/q
            tu/*node*
            '(from :docs {:bind [xt/id {:xt/valid-time (period #inst "2015" #inst "2050")}]
                          :for-valid-time :all-time
                          :for-system-time :all-time})))
        "period column matching literal"))

#_
(t/deftest test-explain-plan-654
  (t/is (= '[{:plan [:project [name age]
                     [:project [{age _r0_age} {name _r0_name} {pid _r0_pid}]
                      [:rename {age _r0_age, name _r0_name, pid _r0_pid}
                       [:project [{pid xt/id} name age]
                        [:scan {:table people, :for-valid-time nil, :for-system-time nil}
                         [age name {xt/id (= xt/id ?pid)}]]]]]]}]

           (xt/q tu/*node*
                 '{:find [name age]
                   :in [pid]
                   :where [($ :people [{:xt/id pid} name age])]}
                 {:explain? true}))))

#_
(deftest test-unbound-vars
  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: foo"
    (xt/q
     tu/*node*
     '{:find [foo]
       :where [(match :docs {:first-name name})]}))
   "plain logic var in find")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: foo"
    (xt/q
     tu/*node*
     (->
      '{:find [(+ foo 1)]
        :where [(match :docs {:first-name name})]})))
   "logic var within expr in find")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: foo"
    (xt/q
     tu/*node*
     (->
      '{:find [(sum foo)]
        :where [(match :docs {:first-name name})]})))
   "logic var within aggr in find")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: foo"
    (xt/q
     tu/*node*
     (->
      '{:find [(sum (- 64 (+ 20 4 foo)))]
        :where [(match :docs {:first-name name})]})))
   "deeply nested logic var in find")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: baz, bar, foo"
    (xt/q
     tu/*node*
     (->
      '{:find [foo (+ 1 bar) (sum (+ 1 (- 1 baz)))]
        :where [(match :docs {:first-name name})]})))
   "multiple unbound vars in find")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: baz, bar, foo"
    (xt/q
     tu/*node*
     (->
      '{:find [foo (+ 1 bar) (sum (+ 1 (- 1 baz)))]
        :where [(match :docs {:first-name name})]})))
   "multiple unbound vars in find")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in order-by clause must be bound in where: baz"
    (xt/q
     tu/*node*
     (->
      '{:find [name]
        :where [(match :docs {:first-name name})]
        :order-by [[baz :asc]]})))
   "simple order-by var")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in order-by clause must be bound in where: biff, baz, bing"
    (xt/q
     tu/*node*
     (->
      '{:find [name]
        :where [(match :docs {:first-name name})]
        :order-by [[(count biff) :desc] [baz :asc] [bing]]})))
   "multiple unbound vars in order-by")

  (t/is
   (thrown-with-msg?
    IllegalArgumentException
    #"Logic variables in find clause must be bound in where: age, min_age"
    (xt/q tu/*node*
          '{:find [min_age age]
            :where [(q {:find [(min age)]
                        :where [($ :docs {:age age})]})]}))
   "variables not exposed by subquery"))

#_
(t/deftest test-default-valid-time
  (xt/submit-tx tu/*node* [[:put :docs {:xt/id 1 :foo "2000-4000"} {:for-valid-time [:in #inst "2000" #inst "4000"]}]
                           [:put :docs {:xt/id 1 :foo "3000-"} {:for-valid-time [:from #inst "3000"]}]])

  (t/is (= #{{:xt/id 1, :foo "2000-4000"} {:xt/id 1, :foo "3000-"}}
           (set (xt/q tu/*node*
                      '{:find [xt/id foo]
                        :where [(match :docs [xt/id foo])]}
                      {:default-all-valid-time? true})))))

#_
(t/deftest test-sql-insert
  (xt/submit-tx tu/*node* [[:sql "INSERT INTO foo (xt$id) VALUES (0)"]])
  (t/is (= [{:xt/id 0}]
           (xt/q tu/*node*
                 '{:find [xt/id]
                   :where [(match :foo [xt/id])]}))))


#_
(t/deftest test-dml-insert
  (xt/submit-tx tu/*node* [[:put :foo {:xt/id 0}]])
  (t/is (= [{:id 0}]
           (xt/q tu/*node*
                 '{:find [id]
                   :where [(match :foo {:xt/id id})]}))))

#_
(t/deftest test-metadata-filtering-for-time-data-607
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 1}})]
    (xt/submit-tx node [[:put :docs {:xt/id 1 :from-date #time/date "2000-01-01"}]
                        [:put :docs {:xt/id 2 :from-date #time/date "3000-01-01"}]])
    (t/is (= [{:id 1}]
             (xt/q node
                   '{:find [id]
                     :where [(match :docs [{:xt/id id} from-date])
                             [(>= from-date #inst "1500")]
                             [(< from-date #inst "2500")]]})))
    (xt/submit-tx node [[:put :docs2 {:xt/id 1 :from-date #inst "2000-01-01"}]
                        [:put :docs2 {:xt/id 2 :from-date #inst "3000-01-01"}]])
    (t/is (= [{:id 1}]
             (xt/q node
                   '{:find [id]
                     :where [(match :docs2 [{:xt/id id} from-date])
                             [(< from-date #time/date "2500-01-01")]
                             [(< from-date #time/date "2500-01-01")]]})))))

#_
(t/deftest bug-non-namespaced-nested-keys-747
  (xt/submit-tx tu/*node* [[:put :bar {:xt/id 1 :foo {:a/b "foo"}}]])
  (t/is (= [{:foo {:a/b "foo"}}]
           (xt/q tu/*node*
                 '{:find [foo]
                   :where [(match :bar [foo])]}))))

#_
(t/deftest test-row-alias
  (let [docs [{:xt/id 42, :firstname "bob"}
              {:xt/id 43, :firstname "alice", :lastname "carrol"}
              {:xt/id 44, :firstname "jim", :orders [{:sku "eggs", :qty 2}, {:sku "cheese", :qty 1}]}]]
    (xt/submit-tx tu/*node* (map (partial vector :put :customer) docs))
    (t/is (= (set (mapv (fn [doc] {:c doc}) docs))
             (set (xt/q tu/*node* '{:find [c] :where [($ :customer {:xt/* c})]}))))))

#_
(t/deftest test-row-alias-system-time-key-set
  (let [inputs
        [[{:xt/id 0, :a 0} #inst "2023-01-17T00:00:00"]
         [{:xt/id 0, :b 0} #inst "2023-01-18T00:00:00"]
         [{:xt/id 0, :c 0, :a 0} #inst "2023-01-19T00:00:00"]]

        _
        (doseq [[doc system-time] inputs]
          (xt/submit-tx tu/*node* [[:put :x doc]] {:system-time system-time}))

        q (partial xt/q tu/*node*)]

    (t/is (= [{:x {:xt/id 0, :a 0, :c 0}}]
             (q '{:find [x]
                  :where [($ :x {:xt/* x})]})))

    (t/is (= [{:x {:xt/id 0, :b 0}}]
             (q '{:find [x]
                  :where [($ :x {:xt/* x})],}
                {:basis {:tx #xt/tx-key {:tx-id 1, :system-time #time/instant "2023-01-18T00:00:00Z"}}})))

    (t/is (= [{:x {:xt/id 0, :a 0}}]
             (q '{:find [x]
                  :where [($ :x {:xt/* x})],}
                {:basis {:tx #xt/tx-key {:tx-id 0, :system-time #time/instant "2023-01-17T00:00:00Z"}}})))))

#_
(t/deftest test-row-alias-app-time-key-set
  (let [inputs
        [[{:xt/id 0, :a 0} #inst "2023-01-17T00:00:00"]
         [{:xt/id 0, :b 0} #inst "2023-01-18T00:00:00"]
         [{:xt/id 0, :c 0, :a 0} #inst "2023-01-19T00:00:00"]]

        _
        (doseq [[doc app-time] inputs]
          (xt/submit-tx tu/*node* [[:put :x doc {:for-valid-time [:in app-time]}]]))

        q (partial xt/q tu/*node*)]

    (t/is (= [{:x {:xt/id 0, :a 0, :c 0}}]
             (q '{:find [x]
                  :where [($ :x {:xt/* x})]})))

    (t/is (= [{:x {:xt/id 0, :b 0}}]
             (q '{:find [x]
                  :where [($ :x {:xt/* x} {:for-valid-time [:at #time/instant "2023-01-18T00:00:00Z"]})],})))

    (t/is (= [{:x {:xt/id 0, :a 0}}]
             (q '{:find [x]
                  :where [($ :x {:xt/* x} {:for-valid-time [:at #time/instant "2023-01-17T00:00:00Z"]})]})))))

#_
(t/deftest test-normalisation
  (xt/submit-tx tu/*node* [[:put :xt-docs {:xt/id "doc" :Foo/Bar 1 :Bar.Foo/hELLo-wORLd 2}]])
  (t/is (= [{:Foo/Bar 1, :Bar.Foo/Hello-World 2}]
           (xt/q tu/*node* '{:find [Foo/Bar Bar.Foo/Hello-World]
                             :where [(match :xt-docs [Foo/Bar Bar.Foo/Hello-World])]})))
  (t/is (= [{:bar 1, :foo 2}]
           (xt/q tu/*node* '{:find [bar foo]
                             :where [(match :xt-docs {:foo/bar bar :bar.foo/hello-world foo})]}))))

#_
(t/deftest test-table-normalisation
  (let [doc {:xt/id "doc" :foo "bar"}]
    (xt/submit-tx tu/*node* [[:put :xt/the-docs doc]])
    (t/is (= [{:id "doc"}]
             (xt/q tu/*node* '{:find [id]
                               :where [(match :xt/the-docs {:xt/id id})]})))
    (t/is (= [{:doc doc}]
             (xt/q tu/*node* '{:find [doc]
                               :where [(match :xt/the-docs {:xt/* doc})]})))))

#_
(t/deftest test-inconsistent-valid-time-range-2494
  (xt/submit-tx tu/*node* '[[:put :xt-docs {:xt/id 1} {:for-valid-time [:in nil #inst "2011"]}]])
  (t/is (= [{:tx-id 0, :committed? false}]
           (xt/q tu/*node* '{:find [tx-id committed?]
                             :where [($ :xt/txs {:xt/id tx-id,
                                                 :xt/committed? committed?})]})))
  (xt/submit-tx tu/*node* '[[:put :xt-docs {:xt/id 2}]])
  (xt/submit-tx tu/*node* '[[:delete :xt-docs 2 {:for-valid-time [:in nil #inst "2011"]}]])
  (t/is (= #{{:tx-id 0, :committed? false}
             {:tx-id 1, :committed? true}
             {:tx-id 2, :committed? false}}
           (set (xt/q tu/*node* '{:find [tx-id committed?]
                                  :where [($ :xt/txs {:xt/id tx-id,
                                                      :xt/committed? committed?})]})))))

(deftest test-date-and-time-literals
  (t/is (= [{:a true, :b false, :c true, :d true}]
           (xt/q tu/*node*
                 '(-> (table [{}] [])
                      (with {:a (= #time/date "2020-01-01" #time/date "2020-01-01")
                             :b (= #time/zoned-date-time "3000-01-01T08:12:13.366Z"
                                   #time/zoned-date-time "2020-01-01T08:12:13.366Z")
                             :c (= #time/date-time "2020-01-01T08:12:13.366"
                                   #time/date-time "2020-01-01T08:12:13.366")
                             :d (= #time/time "08:12:13.366" #time/time "08:12:13.366")}))))))

(t/deftest bug-temporal-queries-wrong-at-boundary-2531
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 10}
                                     :xtdb.tx-producer/tx-producer {:instant-src (tu/->mock-clock)}
                                     :xtdb.log/memory-log {:instant-src (tu/->mock-clock)}})]
    (doseq [i (range 10)]
      (xt/submit-tx node [[:put :ints {:xt/id 0 :n i}]]))

    (t/is (=
           #{{:n 0,
              :valid-time
              {:from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
               :to #time/zoned-date-time "2020-01-02T00:00Z[UTC]"}}
             {:n 1,
              :valid-time
              {:from #time/zoned-date-time "2020-01-02T00:00Z[UTC]",
               :to #time/zoned-date-time "2020-01-03T00:00Z[UTC]"}}
             {:n 2,
              :valid-time
              {:from #time/zoned-date-time "2020-01-03T00:00Z[UTC]",
               :to #time/zoned-date-time "2020-01-04T00:00Z[UTC]"}}
             {:n 3,
              :valid-time
              {:from #time/zoned-date-time "2020-01-04T00:00Z[UTC]",
               :to #time/zoned-date-time "2020-01-05T00:00Z[UTC]"}}
             {:n 4,
              :valid-time
              {:from #time/zoned-date-time "2020-01-05T00:00Z[UTC]",
               :to #time/zoned-date-time "2020-01-06T00:00Z[UTC]"}}}
           (set (xt/q node '(from :ints {:bind [{:n n :xt/id 0 :xt/valid-time valid-time}]
                                         :for-valid-time (in #inst "2020-01-01" #inst "2020-01-06")})))))))

(deftest test-no-zero-width-intervals
  (xt/submit-tx tu/*node* [[:put :xt-docs {:xt/id 1 :v 1}]
                           [:put :xt-docs {:xt/id 1 :v 2}]
                           [:put :xt-docs {:xt/id 2 :v 1} {:for-valid-time [:in #inst "2020-01-01" #inst "2020-01-02"]}]])
  (xt/submit-tx tu/*node* [[:put :xt-docs {:xt/id 2 :v 2} {:for-valid-time [:in #inst "2020-01-01" #inst "2020-01-02"]}]])
  (t/is (= [{:v 2}]
           (xt/q tu/*node*
                 '(from :xt-docs {:bind [{:xt/id 1} v] :for-system-time :all-time})))
        "no zero width system time intervals")
  (t/is (= [{:v 2}]
           (xt/q tu/*node*
                 '(from :xt-docs {:bind [{:xt/id 2} v] :for-valid-time :all-time})))
        "no zero width valid-time intervals"))

#_
(deftest row-alias-on-txs-tables-2809
  ;;TODO from *
  (xt/submit-tx tu/*node* [[:put :xt-docs {:xt/id 1 :v 1}]])
  (xt/submit-tx tu/*node* [[:call :non-existing-fn]])

  (let [txs (->> (xt/q tu/*node*
                       '{:find [tx]
                         :where [(match :xt/txs {:xt/* tx})]})
                 (mapv :tx))]

    (t/is (= [{:xt/tx_time #time/zoned-date-time "2020-01-02T00:00Z[UTC]",
               :xt/id 1,
               :xt/committed? false}
              {:xt/tx_time #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
               :xt/id 0,
               :xt/committed? true}]
             (mapv #(dissoc % :xt/error) txs)))

    (t/is (= {:xtdb.error/error-type :runtime-error,
              :xtdb.error/error-key :xtdb.call/no-such-tx-fn,
              :xtdb.error/message "Runtime error: ':xtdb.call/no-such-tx-fn'",
              :fn-id :non-existing-fn}
             (.getData ^xtdb.RuntimeException (.form ^ClojureForm (:xt/error (first txs))))))))

(deftest test-pull
  (xt/submit-tx tu/*node* [[:put :customers {:xt/id 0, :name "bob"}]
                           [:put :customers {:xt/id 1, :name "alice"}]
                           [:put :orders {:xt/id 0, :customer-id 0}]
                           [:put :orders {:xt/id 1, :customer-id 0}]
                           [:put :orders {:xt/id 2, :customer-id 1}]])


  (t/is (=
         #{{:customer-id 1, :customer {:name "alice"}, :id 2}
           {:customer-id 0, :customer {:name "bob"}, :id 1}
           {:customer-id 0, :customer {:name "bob"}, :id 0}}
         (set (xt/q tu/*node*
                    '(-> (from :orders [{:xt/id id} customer-id])
                         (with {:customer (pull (from :customers [name {:xt/id $customer-id}])
                                                {:args [customer-id]})}))))))

  (t/is (=
         #{{:orders [{:id 1} {:id 0}], :name "bob", :id 0}
           {:orders [{:id 2}], :name "alice", :id 1}}
         (set (xt/q tu/*node*
                    '(-> (from :customers [{:xt/id id} name])
                         (with {:orders (pull* (from :orders [{:customer-id $c-id} {:xt/id id}])
                                               {:args [{:c-id id}]})}))))))
  (t/is (=
         [{:orders nil}]
         (xt/q tu/*node*
               '(unify
                 (with {orders (pull (table [] []))})))))

  (t/is (=
         [{:orders {}}]
         (xt/q tu/*node*
               '(unify
                 (with {orders (pull (table [{}] []))})))))

  (t/is (=
         [{:orders nil}]
         (xt/q tu/*node*
               '(unify
                 (with {orders (pull* (table [] []))})))))

  (t/is (=
         [{:orders [{}]}]
         (xt/q tu/*node*
               '(unify
                 (with {orders (pull* (table [{}] []))}))))))
