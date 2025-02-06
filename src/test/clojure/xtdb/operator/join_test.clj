(ns xtdb.operator.join-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :as t :refer [deftest]]
            [xtdb.logical-plan :as lp]
            [xtdb.operator.join :as join]
            [xtdb.test-util :as tu]))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-cross-join
  (t/is (= {:res [{{:a 12, :b 10, :c 1} 2,
                   {:a 12, :b 15, :c 2} 2,
                   {:a 0, :b 10, :c 1} 1,
                   {:a 0, :b 15, :c 2} 1}
                  {{:a 100, :b 10, :c 1} 1, {:a 100, :b 15, :c 2} 1}
                  {{:a 12, :b 83, :c 3} 2, {:a 0, :b 83, :c 3} 1}
                  {{:a 100, :b 83, :c 3} 1}]
            :col-types '{a :i64, b :i64, c :i64}}
           (-> (tu/query-ra [:cross-join
                             [::tu/pages
                              [[{:a 12}, {:a 12}, {:a 0}]
                               [{:a 100}]]]
                             [::tu/pages
                              [[{:b 10 :c 1}, {:b 15 :c 2}]
                               [{:b 83 :c 3}]]]]
                            {:with-col-types? true, :preserve-pages? true})
               (update :res #(mapv frequencies %)))))

  (t/is (= {:res []
            :col-types '{a :i64, b :i64}}
           (tu/query-ra [:cross-join
                         [::tu/pages
                          [[{:a 12}, {:a 0}]
                           [{:a 100}]]]
                         [::tu/pages '{b :i64} []]]
                        {:with-col-types? true, :preserve-pages? true}))
        "empty input and output")

  (t/is (= {:res [[{} {} {} {} {} {}]]
            :col-types {}}
           (tu/query-ra [:cross-join
                         [::tu/pages [[{} {} {}]]]
                         [::tu/pages [[{} {}]]]]
                        {:with-col-types? true, :preserve-pages? true}))
        "tables with no cols"))

(t/deftest test-equi-join
  (t/is (= {:res [#{{:a 12, :b 12}}
                  #{{:a 100, :b 100}
                    {:a 0, :b 0}}]
            :col-types '{a :i64, b :i64}}
           (-> (tu/query-ra [:join '[{a b}]
                             [::tu/pages
                              [[{:a 12}, {:a 0}]
                               [{:a 100}]]]
                             [::tu/pages
                              [[{:b 12}, {:b 2}]
                               [{:b 100} {:b 0}]]]]
                            {:with-col-types? true, :preserve-pages? true})
               (update :res (partial mapv set)))))

  (t/is (= {:res [{{:a 12} 2}
                  {{:a 100} 1, {:a 0} 1}]
            :col-types '{a :i64}}
           (-> (tu/query-ra [:join '[{a a}]
                             [::tu/pages
                              [[{:a 12}, {:a 0}]
                               [{:a 12}, {:a 100}]]]
                             [::tu/pages
                              [[{:a 12}, {:a 2}]
                               [{:a 100} {:a 0}]]]]
                            {:with-col-types? true, :preserve-pages? true})
               (update :res (partial mapv frequencies))))
        "same column name")

  (t/is (= {:res []
            :col-types '{a :i64, b :i64}}
           (tu/query-ra [:join '[{a b}]
                         [::tu/pages
                          [[{:a 12}, {:a 0}]
                           [{:a 100}]]]
                         [::tu/pages '{b :i64}
                          []]]
                        {:with-col-types? true, :preserve-pages? true}))
        "empty input")

  (t/is (= {:res []
            :col-types '{a :i64, b :i64, c :i64}}
           (tu/query-ra [:join '[{a b}]
                         [::tu/pages
                          [[{:a 12}, {:a 0}]
                           [{:a 100}]]]
                         [::tu/pages
                          [[{:b 10 :c 1}, {:b 15 :c 2}]
                           [{:b 83 :c 3}]]]]
                        {:with-col-types? true, :preserve-pages? true}))
        "empty output"))

(t/deftest test-equi-join-multi-col
  (->> "multi column"
       (t/is (= [{{:a 12, :b 42, :c 12, :d 42, :e 0} 2}
                 {{:a 12, :b 42, :c 12, :d 42, :e 0} 4
                  {:a 12, :b 42, :c 12, :d 42, :e 1} 2}]
                (->> (tu/query-ra [:join '[{a c} {b d}]
                                   [::tu/pages
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]]
                                   [::tu/pages
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]]]
                                  {:preserve-pages? true})
                     (mapv frequencies))))))

(t/deftest test-theta-inner-join
  (t/is (= [{{:a 12, :b 44, :c 12, :d 43} 1}]
           (->> (tu/query-ra [:join '[{a c} (> b d)]
                              [::tu/pages
                               [[{:a 12, :b 42}
                                 {:a 12, :b 44}
                                 {:a 10, :b 42}]]]
                              [::tu/pages
                               [[{:c 12, :d 43}
                                 {:c 11, :d 42}]]]]
                             {:preserve-pages? true})
                (mapv frequencies)))))

(t/deftest test-semi-equi-join
  (t/is (= [{{:a 12} 2} {{:a 100} 1}]
           (->> (tu/query-ra [:semi-join '[{a b}]
                              [::tu/pages
                               [[{:a 12}, {:a 12}, {:a 0}]
                                [{:a 100}]]]
                              [::tu/pages
                               [[{:b 12}, {:b 2}]
                                [{:b 100}]]]]
                             {:preserve-pages? true})
                (mapv frequencies))))

  (t/testing "empty input"
    (t/is (empty? (tu/query-ra [:semi-join '[{a b}]
                                [::tu/pages
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]]
                                [::tu/pages '{b :i64} []]])))

    (t/is (empty? (tu/query-ra [:semi-join '[{a b}]
                                [::tu/pages '{a :i64} []]
                                [::tu/pages
                                 [[{:b 12}, {:b 2}]
                                  [{:b 100} {:b 0}]]]])))

    (t/is (empty? (tu/query-ra [:semi-join '[{a b}]
                                [::tu/pages
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]]
                                [::tu/pages '{b :i64}
                                 [[]]]]))))

  (t/testing "nulls"
    (t/is (= []
             (tu/query-ra [:semi-join '[{a b}]
                           [:table [{:a nil}]]
                           [:table [{:b 12}, {:b 2}]]])))

    (t/is (= [{:a 12}]
             (tu/query-ra [:semi-join '[{a b}]
                           [:table [{:a 12}]]
                           [:table [{:b 12}, {:b nil}]]])))

    (t/is (= []
             (tu/query-ra [:semi-join '[{a b}]
                           [:table [{:a 4}]]
                           [:table [{:b 12}, {:b nil}]]]))))

  (t/is (empty? (tu/query-ra [:semi-join '[{a b}]
                              [::tu/pages
                               [[{:a 12}, {:a 0}]
                                [{:a 100}]]]
                              [::tu/pages
                               [[{:b 10 :c 1}, {:b 15 :c 2}]
                                [{:b 83 :c 3}]]]]))
        "empty output"))

(t/deftest test-semi-equi-join-multi-col
  (->> "multi column semi"
       (t/is (= [{{:a 12, :b 42} 2}]
                (->> (tu/query-ra [:semi-join '[{a c} {b d}]
                                   [::tu/pages
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]]
                                   [::tu/pages
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]]]
                                  {:preserve-pages? true})
                     (mapv frequencies))))))

(t/deftest test-theta-semi-join
  (t/is (= [{{:a 12, :b 44} 1}]
           (->> (tu/query-ra [:semi-join '[{a c} (> b d)]
                              [::tu/pages
                               [[{:a 12, :b 42}
                                 {:a 12, :b 44}
                                 {:a 10, :b 42}]]]
                              [::tu/pages
                               [[{:c 12, :d 43}
                                 {:c 12, :d 43}
                                 {:c 11, :d 42}]]]]
                             {:preserve-pages? true})
                (mapv frequencies)))))

(t/deftest test-mark-equi-join
  (t/is (= {:res [{{:a 12, :m true} 2, {:a 0, :m false} 1} {{:a 100, :m true} 1}]
            :col-types '{a :i64, m [:union #{:null :bool}]}}
           (-> (tu/query-ra [:mark-join '{m [{a b}]}
                             [::tu/pages
                              [[{:a 12}, {:a 12}, {:a 0}]
                               [{:a 100}]]]
                             [::tu/pages
                              [[{:b 12}, {:b 2}]
                               [{:b 100}]]]]
                            {:preserve-pages? true, :with-col-types? true})
               (update :res (partial mapv frequencies)))))

  (t/testing "empty input"
    (t/is (= [[{:a 12, :m false}, {:a 0, :m false}]
              [{:a 100, :m false}, {:m false}]]
             (tu/query-ra [:mark-join '{m [{a b}]}
                           [::tu/pages
                            [[{:a 12}, {:a 0}]
                             [{:a 100}, {:a nil}]]]
                           [::tu/pages '{b :i64} []]]
                          {:preserve-pages? true})))

    (t/is (empty? (tu/query-ra [:mark-join '{m [{a b}]}
                                [::tu/pages '{a :i64} []]
                                [::tu/pages
                                 [[{:b 12}, {:b 2}]
                                  [{:b 100} {:b 0}]]]]))))

  (t/is (= [[{:a 12, :m false}, {:a 0, :m false}]
            [{:a 100, :m false}]]
           (tu/query-ra [:mark-join '{m [{a b}]}
                         [::tu/pages
                          [[{:a 12}, {:a 0}]
                           [{:a 100}]]]
                         [::tu/pages
                          [[{:b 10 :c 1}, {:b 15 :c 2}]
                           [{:b 83 :c 3}]]]]
                        {:preserve-pages? true}))
        "no matches"))

(t/deftest test-mark-join-nils
  (t/is (= [{:a 12, :m true}, {:a 14, :m false}, {}]
           (tu/query-ra [:mark-join '{m [{a b}]}
                         [::tu/pages
                          [[{:a 12}, {:a 14}, {}]]]
                         [::tu/pages
                          [[{:b 12}]]]])))

  (t/is (= [{:a 12, :m true}, {:a 14} {}]
           (tu/query-ra [:mark-join '{m [{a b}]}
                         [::tu/pages
                          [[{:a 12}, {:a 14}, {:a nil}]]]
                         [::tu/pages
                          [[{:b 12}, {:b nil}]]]]))))

(t/deftest test-mark-join-theta
  (t/is (= [{:a 12, :m true}, {:a 14, :m true}, {}]
           (tu/query-ra [:mark-join '{m [(>= a b)]}
                         [::tu/pages
                          [[{:a 12}, {:a 14}, {:a nil}]]]
                         [::tu/pages
                          [[{:b 12}]]]])))

  (t/is (= [{:a 12}, {:a 14, :m true} {}]
           (tu/query-ra [:mark-join '{m [(> a b)]}
                         [::tu/pages
                          [[{:a 12}, {:a 14}, {:a nil}]]]
                         [::tu/pages
                          [[{:b 12}, {:b nil}]]]]))))

(t/deftest test-exists-as-mark-join
  ;;These are arguably not tests of mark-join functionality itself,
  ;;but prove that exist/not-exists can be expressed via mark-join (and a negation)
  (t/testing "exists"
    (t/is (= [{:a 12, :m true}, {:a 14, :m true}, {:m true}]
             (tu/query-ra [:mark-join '{m [true]}
                           [::tu/pages
                            [[{:a 12}, {:a 14}, {:a nil}]]]
                           [::tu/pages
                            [[{:b 1} {:c 2}]]]])))

    (t/is (= [{:a 12, :m true}, {:a 14, :m true}, {:m true}]
             (tu/query-ra [:mark-join '{m [true]}
                           [::tu/pages
                            [[{:a 12}, {:a 14}, {:a nil}]]]
                           [::tu/pages
                            [[{:a nil}]]]])))

    (t/is (= [{:a 12, :m false}, {:a 14, :m false}, {:m false}]
             (tu/query-ra [:mark-join '{m [true]}
                           [::tu/pages
                            [[{:a 12}, {:a 14}, {:a nil}]]]
                           [::tu/pages
                            [[]]]]))))
  (t/testing "not-exists"
    (t/is (= [{:a 12, :m false}, {:a 14, :m false}, {:m false}]
             (tu/query-ra '[:project [a {m (not m)}]
                            [:mark-join {m [true]}
                             [::tu/pages
                              [[{:a 12}, {:a 14}, {:a nil}]]]
                             [::tu/pages
                              [[{:b 1} {:c 2}]]]]])))

    (t/is (= [{:a 12, :m false}, {:a 14, :m false}, {:m false}]
             (tu/query-ra '[:project [a {m (not m)}]
                            [:mark-join {m [true]}
                             [::tu/pages
                              [[{:a 12}, {:a 14}, {:a nil}]]]
                             [::tu/pages
                              [[{:a nil}]]]]])))

    (t/is (= [{:a 12, :m true}, {:a 14, :m true}, {:m true}]
             (tu/query-ra '[:project [a {m (not m)}]
                            [:mark-join {m [true]}
                             [::tu/pages
                              [[{:a 12}, {:a 14}, {:a nil}]]]
                             [::tu/pages
                              [[]]]]])))))

(t/deftest test-left-equi-join
  (t/is (= {:res [{{:a 12, :b 12, :c 2} 1, {:a 12, :b 12, :c 0} 1, {:a 0} 1}
                  {{:a 12, :b 12, :c 2} 1, {:a 100, :b 100, :c 3} 1, {:a 12, :b 12, :c 0} 1}]
            :col-types '{a :i64, b [:union #{:null :i64}], c [:union #{:null :i64}]}}
           (-> (tu/query-ra [:left-outer-join '[{a b}]
                             [::tu/pages
                              [[{:a 12}, {:a 0}]
                               [{:a 12}, {:a 100}]]]
                             [::tu/pages
                              [[{:b 12, :c 0}, {:b 2, :c 1}]
                               [{:b 12, :c 2}, {:b 100, :c 3}]]]]
                            {:preserve-pages? true, :with-col-types? true})
               (update :res (partial mapv frequencies)))))

  (t/testing "empty input"
    (t/is (= {:res [#{{:a 12}, {:a 0}}
                    #{{:a 100}}]
              :col-types '{a :i64, b [:union #{:null :i64}]}}
             (-> (tu/query-ra [:left-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}, {:a 0}]
                                 [{:a 100}]]]
                               [::tu/pages '{b :i64} []]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv set)))))

    (t/is (= {:res []
              :col-types '{a :i64, b [:union #{:null :i64}]}}
             (tu/query-ra [:left-outer-join '[{a b}]
                           [::tu/pages '{a :i64} []]
                           [::tu/pages
                            [[{:b 12}, {:b 2}]
                             [{:b 100} {:b 0}]]]]
                          {:preserve-pages? true, :with-col-types? true})))

    (t/is (= {:res [#{{:a 12}, {:a 0}}
                    #{{:a 100}}]
              :col-types '{a :i64, b [:union #{:null :i64}]}}
             (-> (tu/query-ra [:left-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}, {:a 0}]
                                 [{:a 100}]]]
                               [::tu/pages '{b :i64}
                                [[]]]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv set)))))))

(t/deftest test-left-equi-join-multi-col
  (->> "multi column left"
       (t/is (= [{{:a 11, :b 44} 1
                  {:a 10, :b 42} 1
                  {:a 12, :b 42, :c 12, :d 42, :e 0} 6
                  {:a 12, :b 42, :c 12, :d 42, :e 1} 2}]
                (->> (tu/query-ra [:left-outer-join '[{a c} {b d}]
                                   [::tu/pages
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]]
                                   [::tu/pages
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]]]
                                  {:preserve-pages? true})
                     (mapv frequencies))))))

(t/deftest test-left-theta-join
  (let [left [::tu/pages
              [[{:a 12, :b 42}
                {:a 12, :b 44}
                {:a 10, :b 42}
                {:a 10, :b 42}]]]
        right [::tu/pages
               [[{:c 12, :d 43}
                 {:c 11, :d 42}]]]]

    (t/is (= [{{:a 12, :b 42} 1
               {:a 12, :b 44, :c 12, :d 43} 1
               {:a 10, :b 42} 2}]
             (->> (tu/query-ra [:left-outer-join '[{a c} (> b d)] left right]
                               {:preserve-pages? true})
                  (mapv frequencies))))

    (t/is (= [{{:a 12, :b 42} 1
               {:a 12, :b 44} 1
               {:a 10, :b 42} 2}]
             (->> (tu/query-ra [:left-outer-join '[{a c} (> b d) (= c -1)] left right]
                               {:preserve-pages? true})
                  (mapv frequencies))))

    (t/is (= [{{:a 12, :b 42} 1
               {:a 12, :b 44} 1
               {:a 10, :b 42} 2}]
             (->> (tu/query-ra [:left-outer-join '[{a c} (and (= c -1) (> b d))] left right]
                               {:preserve-pages? true})
                  (mapv frequencies))))

    (t/is (= [{{:a 12, :b 42} 1
               {:a 12, :b 44} 1
               {:a 10, :b 42} 2}]
             (->> (tu/query-ra [:left-outer-join '[{a c} {b d} (= c -1)] left right]
                               {:preserve-pages? true})
                  (mapv frequencies))))))

(t/deftest test-full-outer-join
  (t/testing "missing on both sides"
    (t/is (= {:res [{{:a 12, :b 12, :c 0} 2, {:b 2, :c 1} 1}
                    {{:a 12, :b 12, :c 2} 2, {:a 100, :b 100, :c 3} 1}
                    {{:a 0} 1}]
              :col-types '{a [:union #{:null :i64}], b [:union #{:null :i64}], c [:union #{:null :i64}]}}
             (-> (tu/query-ra [:full-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}, {:a 0}]
                                 [{:a 12}, {:a 100}]]]
                               [::tu/pages
                                [[{:b 12, :c 0}, {:b 2, :c 1}]
                                 [{:b 12, :c 2}, {:b 100, :c 3}]]]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv frequencies)))))

    (t/is (= {:res [{{:a 12, :b 12, :c 0} 2, {:a 12, :b 12, :c 2} 2, {:b 2, :c 1} 1}
                    {{:a 100, :b 100, :c 3} 1}
                    {{:a 0} 1}]
              :col-types '{a [:union #{:null :i64}], b [:union #{:null :i64}], c [:union #{:null :i64}]}}
             (-> (tu/query-ra [:full-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}, {:a 0}]
                                 [{:a 12}, {:a 100}]]]
                               [::tu/pages
                                [[{:b 12, :c 0}, {:b 12, :c 2}, {:b 2, :c 1}]
                                 [{:b 100, :c 3}]]]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv frequencies))))))

  (t/testing "all matched"
    (t/is (= {:res [{{:a 12, :b 12, :c 0} 2, {:a 100, :b 100, :c 3} 1}
                    {{:a 12, :b 12, :c 2} 2}]
              :col-types '{a [:union #{:null :i64}], b [:union #{:null :i64}], c [:union #{:null :i64}]}}
             (-> (tu/query-ra [:full-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}]
                                 [{:a 12}, {:a 100}]]]
                               [::tu/pages
                                [[{:b 12, :c 0}, {:b 100, :c 3}]
                                 [{:b 12, :c 2}]]]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv frequencies)))))

    (t/is (= {:res [{{:a 12, :b 12, :c 0} 2, {:a 12, :b 12, :c 2} 2}
                    {{:a 100, :b 100, :c 3} 1}]
              :col-types '{a [:union #{:null :i64}], b [:union #{:null :i64}], c [:union #{:null :i64}]}}
             (-> (tu/query-ra [:full-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}]
                                 [{:a 12}, {:a 100}]]]
                               [::tu/pages
                                [[{:b 12, :c 0}, {:b 12, :c 2}]
                                 [{:b 100, :c 3}]]]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv frequencies))))))

  (t/testing "empty input"
    (t/is (= {:res [{{:a 0} 1, {:a 100} 1, {:a 12} 1}]
              :col-types '{a [:union #{:null :i64}], b [:union #{:null :i64}]}}
             (-> (tu/query-ra [:full-outer-join '[{a b}]
                               [::tu/pages
                                [[{:a 12}, {:a 0}]
                                 [{:a 100}]]]
                               [::tu/pages '{b :i64} []]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv frequencies)))))

    (t/is (= {:res [{{:b 12} 1, {:b 2} 1}
                    {{:b 100} 1, {:b 0} 1}]
              :col-types '{a [:union #{:null :i64}], b [:union #{:null :i64}]}}
             (-> (tu/query-ra [:full-outer-join '[{a b}]
                               [::tu/pages '{a :i64} []]
                               [::tu/pages
                                [[{:b 12}, {:b 2}]
                                 [{:b 100} {:b 0}]]]]
                              {:preserve-pages? true, :with-col-types? true})
                 (update :res (partial mapv frequencies)))))))

(t/deftest test-full-outer-equi-join-multi-col
  (->> "multi column full outer"
       (t/is (= [{{:a 12 :b 42 :c 12 :d 42 :e 0} 2
                  {:c 11 :d 42 :e 0} 1
                  {:c 12 :d 43 :e 0} 1}
                 {{:a 12 :b 42 :c 12 :d 42 :e 0} 4
                  {:a 12 :b 42 :c 12 :d 42 :e 1} 2}
                 {{:a 10 :b 42} 1
                  {:a 11 :b 44} 1}]
                (->> (tu/query-ra [:full-outer-join '[{a c} {b d}]
                                   [::tu/pages
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]]
                                   [::tu/pages
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]]]
                                  {:preserve-pages? true})
                     (mapv frequencies))))))

(t/deftest test-full-outer-join-theta
  (let [left [::tu/pages
              [[{:a 12, :b 42}
                {:a 12, :b 44}
                {:a 10, :b 42}
                {:a 10, :b 42}]]]
        right [::tu/pages
               [[{:c 12, :d 43}
                 {:c 11, :d 42}]]]]

    (t/is (= {{:a 12, :b 42, :c 12, :d 43} 1
              {:a 12, :b 44} 1
              {:a 10, :b 42} 2
              {:c 11, :d 42} 1}

             (->> (tu/query-ra [:full-outer-join '[{a c} (not (= b 44))] left right])
                  (frequencies))))

    (t/is (= {{:a 12, :b 42} 1
              {:a 12, :b 44} 1
              {:a 10, :b 42} 2
              {:c 12, :d 43} 1
              {:c 11, :d 42} 1}
             (->> (tu/query-ra [:full-outer-join '[{a c} false] left right])
                  (frequencies))))))

(t/deftest test-anti-equi-join
  (t/is (= {:res [{{:a 0} 2}]
            :col-types '{a :i64}}
           (-> (tu/query-ra [:anti-join '[{a b}]
                             [::tu/pages
                              [[{:a 12}, {:a 0}, {:a 0}]
                               [{:a 100}]]]
                             [::tu/pages
                              [[{:b 12}, {:b 2}]
                               [{:b 100}]]]]
                            {:preserve-pages? true, :with-col-types? true})
               (update :res (partial mapv frequencies)))))

  (t/testing "empty input"
    (t/is (empty? (:res (tu/query-ra [:anti-join '[{a b}]
                                      [::tu/pages '{a :i64} []]
                                      [::tu/pages
                                       [[{:b 12}, {:b 2}]
                                        [{:b 100}]]]]))))

    (t/is (= [#{{:a 12}, {:a 0}}
              #{{:a 100}}]
             (->> (tu/query-ra [:anti-join '[{a b}]
                                [::tu/pages
                                 [[{:a 12}, {:a 0}]
                                  [{:a 100}]]]
                                [::tu/pages '{b :i64} []]]
                               {:preserve-pages? true})
                  (mapv set)))))

  (t/testing "nulls"
    (t/is (= []
             (tu/query-ra [:anti-join '[{a b}]
                           [:table [{:a nil}]]
                           [:table [{:b 12}, {:b 2}]]])))

    (t/is (= []
             (tu/query-ra [:anti-join '[{a b}]
                           [:table [{:a 12}]]
                           [:table [{:b 12}, {:b nil}]]])))

    (t/is (= []
             (tu/query-ra [:anti-join '[{a b}]
                           [:table [{:a 4}]]
                           [:table [{:b 12}, {:b nil}]]]))))

  (t/is (empty? (tu/query-ra [:anti-join '[{a b}]
                              [::tu/pages
                               [[{:a 12}, {:a 2}]
                                [{:a 100}]]]
                              [::tu/pages
                               [[{:b 12}, {:b 2}]
                                [{:b 100}]]]]))
        "empty output"))

(t/deftest test-anti-equi-join-multi-col
  (->> "multi column anti"
       (t/is (= [{{:a 10 :b 42} 1
                  {:a 11 :b 44} 1}]
                (->> (tu/query-ra [:anti-join '[{a c} {b d}]
                                   [::tu/pages
                                    [[{:a 12, :b 42}
                                      {:a 12, :b 42}
                                      {:a 11, :b 44}
                                      {:a 10, :b 42}]]]
                                   [::tu/pages
                                    [[{:c 12, :d 42, :e 0}
                                      {:c 12, :d 43, :e 0}
                                      {:c 11, :d 42, :e 0}]
                                     [{:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 0}
                                      {:c 12, :d 42, :e 1}]]]]
                                  {:preserve-pages? true})
                     (mapv frequencies))))))

(t/deftest test-anti-join-theta
  (let [left [::tu/pages
              [[{:a 12, :b 42}
                {:a 12, :b 44}
                {:a 10, :b 42}
                {:a 10, :b 42}]]]
        right [::tu/pages
               [[{:c 12, :d 43}
                 {:c 11, :d 42}]]]]

    (t/is (= {{:a 12, :b 44} 1
              {:a 10, :b 42} 2}

             (->> (tu/query-ra [:anti-join '[{a c} (not (= b 44))] left right])
                  (frequencies))))

    (t/is (= {{:a 12, :b 42} 1
              {:a 12, :b 44} 1
              {:a 10, :b 42} 2}
             (->> (tu/query-ra [:anti-join '[{a c} false] left right])
                  (frequencies))))))

(t/deftest test-join-on-true
  (letfn [(run-join [left? right? theta-expr]
            (->> (tu/query-ra [:join [theta-expr]
                               [::tu/pages '{a :i64}
                                (if left? [[{:a 12}, {:a 0}]] [])]
                               [::tu/pages '{b :i64}
                                (if right? [[{:b 12}, {:b 2}]] [])]]
                              {:preserve-pages? true})
                 (mapv frequencies)))]
    (t/is (= []
             (run-join true false nil)))

    (t/is (= []
             (run-join true false true)))

    (t/is (= []
             (run-join true true false)))

    (t/is (= []
             (run-join true true nil)))

    (t/is (= [{{:a 12, :b 12} 1,
               {:a 12, :b 2} 1,
               {:a 0, :b 12} 1,
               {:a 0, :b 2} 1}]
             (run-join true true true)))))

(t/deftest test-loj-on-true
  (letfn [(run-loj [left? right? theta-expr]
            (->> (tu/query-ra [:left-outer-join [theta-expr]
                               [::tu/pages '{a :i64}
                                (if left? [[{:a 12}, {:a 0}]] [])]
                               [::tu/pages '{b :i64}
                                (if right? [[{:b 12}, {:b 2}]] [])]]
                              {:preserve-pages? true})
                 (mapv frequencies)))]
    (t/is (= [{{:a 12} 1,
               {:a 0} 1}]
             (run-loj true false nil)))

    (t/is (= [{{:a 12} 1,
               {:a 0} 1}]
             (run-loj true false true)))

    (t/is (= []
             (run-loj false true nil)))

    (t/is (= []
             (run-loj false true true)))

    (t/is (= [{{:a 12} 1,
               {:a 0} 1}]
             (run-loj true true false)))

    (t/is (= [{{:a 12} 1,
               {:a 0} 1}]
             (run-loj true true nil)))

    (t/is (= [{{:a 12, :b 12} 1,
               {:a 12, :b 2} 1,
               {:a 0, :b 12} 1,
               {:a 0, :b 2} 1}]
             (run-loj true true true)))))

(t/deftest test-foj-on-true
  (letfn [(run-foj [left? right? theta-expr]
            (->> (tu/query-ra [:full-outer-join [theta-expr]
                               [::tu/pages '{a :i64}
                                (if left? [[{:a 12}, {:a 0}]] [])]
                               [::tu/pages '{b :i64}
                                (if right? [[{:b 12}, {:b 2}]] [])]]
                              {:preserve-pages? true})
                 (mapv frequencies)))]
    (t/is (= [{{:a 12} 1,
               {:a 0} 1}]
             (run-foj true false nil)))

    (t/is (= [{{:a 12} 1,
               {:a 0} 1}]
             (run-foj true false true)))

    (t/is (= [{{:b 12} 1,
               {:b 2} 1}]
             (run-foj false true nil)))

    (t/is (= [{{:b 12} 1,
               {:b 2} 1}]
             (run-foj false true true)))

    (t/is (= [{{:b 12} 1,
               {:b 2} 1}
              {{:a 12} 1,
               {:a 0} 1}]
             (run-foj true true false)))

    (t/is (= [{{:b 12} 1,
               {:b 2} 1}
              {{:a 12} 1,
               {:a 0} 1}]
             (run-foj true true nil)))

    (t/is (= [{{:a 12, :b 12} 1,
               {:a 12, :b 2} 1,
               {:a 0, :b 12} 1,
               {:a 0, :b 2} 1}]
             (run-foj true true true)))))

(t/deftest test-semi-join-on-true
  (letfn [(run-semi [left? right? theta-expr]
            (->> (tu/query-ra [:semi-join [theta-expr]
                               [::tu/pages '{a :i64}
                                (if left? [[{:a 12}, {:a 0}]] [])]

                               [::tu/pages '{b :i64}
                                (if right? [[{:b 12}, {:b 2}]] [])]]
                              {:preserve-pages? true})
                 (mapv frequencies)))]
    (t/is (= [] (run-semi true false nil)))
    (t/is (= [] (run-semi true false true)))
    (t/is (= [] (run-semi false true nil)))
    (t/is (= [] (run-semi false true true)))
    (t/is (= [] (run-semi true true false)))

    (t/is (= [] (run-semi true true nil)))
    (t/is (= [{{:a 12} 1, {:a 0} 1}] (run-semi true true true)))))

(t/deftest test-anti-join-on-true
  (letfn [(run-anti [left? right? theta-expr]
            (->> (tu/query-ra [:anti-join [theta-expr]
                               [::tu/pages '{a :i64}
                                (if left? [[{:a 12}, {:a 0}]] [])]
                               [::tu/pages '{b :i64}
                                (if right? [[{:b 12}, {:b 2}]] [])]]
                              {:preserve-pages? true})
                 (mapv frequencies)))]
    (t/is (= [{{:a 12} 1, {:a 0} 1}] (run-anti true false nil)))
    (t/is (= [{{:a 12} 1, {:a 0} 1}] (run-anti true false true)))

    (t/is (= [] (run-anti false true nil)))
    (t/is (= [] (run-anti false true true)))

    (t/is (= [{{:a 12} 1, {:a 0} 1}] (run-anti true true false)))
    (t/is (= [] (run-anti true true nil)))

    (t/is (= [] (run-anti true true true)))))

(t/deftest test-equi-join-expr
  (letfn [(test-equi-join [join-specs left-vals right-vals]
            (tu/query-ra [:join join-specs
                          [::tu/pages [left-vals]]
                          [::tu/pages [right-vals]]]
                         {}))]
    (t/is (= [{:a 42, :b 42}]
             (test-equi-join '[{(+ a 1) (+ b 1)}]
                             [{:a 42}] [{:b 42} {:b 43}])))

    (t/is (= []
             (test-equi-join '[{(+ a 1) (+ b 1)}]
                             [{:a 42}] [{:b 43} {:b 44}])))
    (t/testing "either side can be a plain column ref"
      (t/is (= [{:a 42, :b 43}] (test-equi-join '[{a (- b 1)}] [{:a 42}] [{:b 43} {:b 44}])))
      (t/is (= [{:a 42, :b 43}] (test-equi-join '[{(+ a 1) b}] [{:a 42}] [{:b 43} {:b 44}]))))))

(t/deftest test-single-join
  (t/is (= [{:x 0, :y 0, :z 0} {:x 0, :y 1, :z 0} {:x 1, :y 2, :z 1}]
           (tu/query-ra '[:single-join [{x z}]
                          [::tu/pages [[{:x 0, :y 0}, {:x 0, :y 1}, {:x 1, :y 2}]]]
                          [::tu/pages [[{:z 0}, {:z 1}]]]])))

  (t/is (thrown-with-msg? RuntimeException
                          #"cardinality violation"
                          (tu/query-ra '[:single-join [{x y}]
                                         [::tu/pages [[{:x 0}, {:x 0}, {:x 1}, {:x 2}]]]
                                         [::tu/pages [[{:y 0}, {:y 1}, {:y 1}]]]]))
        "throws on cardinality > 1")

  (t/testing "empty input"
    (t/is (= []
             (tu/query-ra '[:single-join [{x y}]
                            [::tu/pages {x :i64} []]
                            [::tu/pages [[{:y 0}, {:y 1}, {:y 1}]]]])))

    (t/is (= [{:x 0}]
             (tu/query-ra '[:single-join [{x y}]
                            [::tu/pages [[{:x 0}]]]
                            [::tu/pages {y :i64} []]])))))

(t/deftest test-mega-join
  (t/is (= [{:x1 1, :x2 1, :x4 3, :x3 1}]
           (tu/query-ra
            '[:mega-join
              [{x1 x2}
               (> (+ x1 10) (+ (+ x3 2) x4))
               {x1 x3}]
              [[::tu/pages [[{:x1 1}]]]
               [::tu/pages [[{:x2 1}]]]
               [::tu/pages [[{:x3 1 :x4 3}]]]]])))

  (t/testing "disconnected relations/sub-graphs"
    (t/is (= [{:x1 1, :x2 1, :x4 3, :x3 1, :x5 5, :x6 10, :x7 10, :x8 8}]
             (tu/query-ra
              '[:mega-join
                [{x1 x2}
                 (> (+ x1 10) (+ (+ x3 2) x4))
                 {x1 x3}
                 {x6 x7}]
                [[::tu/pages [[{:x1 1}]]]
                 [::tu/pages [[{:x2 1}]]]
                 [::tu/pages [[{:x3 1 :x4 3}]]]
                 [::tu/pages [[{:x5 5}]]]
                 [::tu/pages [[{:x6 10}]]]
                 [::tu/pages [[{:x7 10}]]]
                 [::tu/pages [[{:x8 8}]]]]]))))

  (t/testing "params"
    (t/is (= [{:x1 1, :x2 1, :x4 3, :x3 1, :x5 2}]
             (tu/query-ra
              '[:apply :cross-join {x5 ?x2}
                [::tu/pages [[{:x5 2}]]]
                [:mega-join
                 [{x1 (- ?x2 1)}
                  (> (+ x1 10) (+ (+ x3 2) x4))
                  {x1 x3}]
                 [[::tu/pages [[{:x1 1}]]]
                  [::tu/pages [[{:x2 1}]]]
                  [::tu/pages [[{:x3 1 :x4 3}]]]]]])))

    (t/is (= [{:baz 10, :bar 1}]
             (tu/query-ra
              '[:mega-join
                [{bar (- ?foo 0)}]
                [[:table [{:bar 1}]]
                 [:table [{:baz 10}]]]]
              {:args {:foo 1}}))))

  (t/testing "empty input"
    (t/is (= []
             (tu/query-ra
              '[:mega-join
                [{x1 x2}
                 (> (+ x1 10) (+ (+ x3 2) x4))
                 {x1 x3}]
                [[::tu/pages {x1 :i64} []]
                 [::tu/pages [[{:x2 1}]]]
                 [::tu/pages [[{:x3 1 :x4 3}]]]]]))))

  (t/testing "symbol to symbol equi joins do not require columns to begin with x"
    (t/is (= [{:person 1, :bar "woo", :name 1, :foo 1}
              {:person 1, :bar "yay", :name 1, :foo 1}]
             (tu/query-ra
              '[:mega-join
                [{name person}
                 {person foo}]
                [[:table [{:name 1}]]
                 [:table [{:person 1}]]
                 [:table [{:foo 1 :bar "woo"}
                          {:foo 1 :bar "yay"}]]]])))

    (t/testing "Unused join conditions are added as conditions to the outermost join"
      ;;currently mega-join starts with the smallest (in terms of row count) relation
      ;;and will only add connected relations to that sub-graph. In this case we would
      ;;need to cross join the first 2 tables before joining the third.
      ;;
      ;;Instead we produce unconnected subgraphs (in this case 3) and then add the
      ;;join conditions to the outermost join.
      (t/is (= '[[0] [1] ([:pred-expr (= (+ name person) foo)]) [2]]
               (:join-order
                (lp/emit-expr
                 (s/conform ::lp/logical-plan
                            '[:mega-join
                              [(= (+ name person) foo)]
                              [[:table [{:name 1}]]
                               [:table [{:person 1}]]
                               [:table [{:foo 2 :bar "woo"}
                                        {:foo 1 :bar "yay"}]]]])
                 {}))))
      (t/is (= [{:person 1, :bar "woo", :name 1, :foo 2}]
               (tu/query-ra
                '[:mega-join
                  [(= (+ name person) foo)]
                  [[:table [{:name 1}]]
                   [:table [{:person 1}]]
                   [:table [{:foo 2 :bar "woo"}
                            {:foo 1 :bar "yay"}]]]]))))))

(t/deftest test-adjust-to-equi-condition
  (t/is
   (= '[:equi-condition {t0_n t1_n}]
      (join/adjust-to-equi-condition
       '{:cols #{t1_n t0_n},
         :condition [:pred-expr (= t1_n t0_n)],
         :condition-id 0,
         :cols-from-current-rel #{t0_n},
         :other-cols #{t1_n},
         :all-cols-present? true}))))

(deftest test-mega-join-join-order
  (t/is
   (=
    '[[2 [[:equi-condition {foo bar}]]
       3 [[:equi-condition {foo baz}]]
       0 [[:equi-condition {bar biff}]]
       1]]
    (:join-order
     (lp/emit-expr
      '{:op :mega-join,
        :conditions
        [[:equi-condition {bar biff}]
         [:equi-condition {foo baz}]
         [:equi-condition {foo bar}]],
        :relations
        [{:op ::tu/pages,
          :stats {:row-count 3}
          :pages [[{:baz 1}]]}
         {:op ::tu/pages,
          :stats nil
          :pages [[{:biff 1}]]}
         {:op ::tu/pages,
          :stats {:row-count 1}
          :pages [[{:foo 1}]]}
         {:op ::tu/pages,
          :stats {:row-count 2}
          :pages [[{:bar 1}]]}]}
      {}))))

  (t/testing "disconnected-sub-graphs"
    (t/is
     (=
      '[[1 [[:equi-condition {foo baz}]] 0] [2]]
      (:join-order
       (lp/emit-expr
        '{:op :mega-join,
          :conditions
          [[:equi-condition {foo baz}]],
          :relations
          [{:op ::tu/pages,
            :stats {:row-count 3}
            :pages [[{:baz 1}]]}
           {:op ::tu/pages,
            :stats {:row-count 1}
            :pages [[{:foo 1}]]}
           {:op ::tu/pages,
            :stats {:row-count 2}
            :pages [[{:bar 1}]]}]}
        {}))))))

(t/deftest can-join-on-lists-491
  (t/is (= [{:id [1 2], :foo 0, :bar 5}]
           (tu/query-ra
            '[:join [{id id}]
              [::tu/pages [[{:id [1 2], :foo 0}
                              {:id [1 3], :foo 1}]]]
              [::tu/pages [[{:id [1 2], :bar 5}]]]]))))

(t/deftest can-join-on-structs-491
  (t/is (= [{:id {:a 1, :b 2}, :foo 0, :bar 5}]
           (tu/query-ra
            '[:join [{id id}]
              [::tu/pages [[{:id {:a 1, :b 2}, :foo 0}
                              {:id {:a 1, :b 3}, :foo 1}]]]
              [::tu/pages [[{:id {:a 1, :b 2}, :bar 5}]]]]))))
