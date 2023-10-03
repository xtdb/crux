(ns xtdb.operator.scan-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.node :as node]
            [xtdb.operator :as op]
            [xtdb.operator.scan :as scan]
            [xtdb.test-util :as tu]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import (java.util.function IntPredicate)
           xtdb.operator.IRaQuerySource
           xtdb.operator.IRelationSelector
           xtdb.vector.RelationReader))

(t/use-fixtures :each tu/with-mock-clock tu/with-allocator tu/with-node)

(t/deftest test-simple-scan
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id :foo, :col1 "foo1"}]
                        [:put :xt_docs {:xt/id :bar, :col1 "bar1", :col2 "bar2"}]
                        [:put :xt_docs {:xt/id :foo, :col2 "baz2"}]])

    (t/is (= #{{:xt/id :bar, :col1 "bar1", :col2 "bar2"}
               {:xt/id :foo, :col2 "baz2"}}
             (set (tu/query-ra '[:scan {:table xt_docs} [xt/id col1 col2]]
                               {:node node}))))))

(t/deftest test-simple-scan-with-namespaced-attributes
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id :foo, :the-ns/col1 "foo1"}]
                        [:put :xt_docs {:xt/id :bar, :the-ns/col1 "bar1", :col2 "bar2"}]
                        [:put :xt_docs {:xt/id :foo, :the-ns/col2 "baz2"}]])

    (t/is (= #{{:xt/id :bar, :the-ns/col1 "bar1", :col2 "bar2"}
               {:xt/id :foo}}
             (set (tu/query-ra '[:scan {:table xt_docs} [xt/id the-ns/col1 col2]]
                               {:node node}))))))

(t/deftest test-duplicates-in-scan-1
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id :foo}]])

    (t/is (= [{:xt/id :foo}]
             (tu/query-ra '[:scan {:table xt_docs} [xt/id xt/id]]
                          {:node node})))))

(t/deftest test-chunk-boundary
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 20}})]
    (->> (for [i (range 110)]
           [:put :xt_docs {:xt/id i}])
         (partition-all 10)
         (mapv #(xt/submit-tx node %)))

    (t/is (= (set (for [i (range 110)] {:xt/id i}))
             (set (tu/query-ra '[:scan {:table xt_docs} [xt/id]]
                               {:node node}))))))

(t/deftest test-smaller-page-limit
  (with-open [node (node/start-node {:xtdb.indexer/live-index {:page-limit 16}})]
    (xt/submit-tx node (for [i (range 20)] [:put :xt_docs {:xt/id i}]))

    (tu/finish-chunk! node)

    (t/is (= (into #{} (map #(hash-map :xt/id %)) (range 20))
             (set (tu/query-ra '[:scan {:table xt_docs} [xt/id]]
                               {:node node}))))))

(t/deftest test-metadata
  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 20}})]
    (->> (for [i (range 100)]
           [:put :xt_docs {:xt/id i}])
         (partition-all 20)
         (mapv #(xt/submit-tx node %)))

    (t/is (= (set (concat (for [i (range 20)] {:xt/id i}) (for [i (range 80 100)] {:xt/id i})))
             (set (tu/query-ra '[:scan {:table xt_docs} [{xt/id (or (< xt/id 20)
                                                                    (>= xt/id 80))}]]
                               {:node node})))
          "testing only getting some trie matches"))

  (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 20}})]
    (xt/submit-tx node (for [i (range 20)] [:put :xt_docs {:xt/id i}]))
    (xt/submit-tx node (for [i (range 20)] [:delete :xt_docs i]))

    (t/is (= []
             (tu/query-ra '[:scan {:table xt_docs} [{xt/id (< xt/id 20)}]]
                          {:node node}))
          "testing newer chunks relevant even if not matching")

    (t/is (= []
             (tu/query-ra '[:scan {:table xt_docs} [toto]]
                          {:node node}))
          "testing nothing matches"))

  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id 1 :boolean-or-int true}]
                        [:put :xt_docs {:xt/id 2 :boolean-or-int 2}]])
    (tu/finish-chunk! node)

    (t/is (= [{:boolean-or-int true}]
             (tu/query-ra '[:scan {:table xt_docs} [{boolean-or-int (= boolean-or-int true)}]]
                          {:node node}))
          "testing boolean metadata")))

(t/deftest test-past-point-point-queries
  (with-open [node (node/start-node {})]
    (let [tx1 (xt/submit-tx node [[:put :xt_docs {:xt/id :doc1 :v 1} {:for-valid-time [:from #inst "2015"]}]
                                  [:put :xt_docs {:xt/id :doc2 :v 1} {:for-valid-time [:from #inst "2015"]}]
                                  [:put :xt_docs {:xt/id :doc3 :v 1} {:for-valid-time [:from #inst "2018"]}]])

          tx2 (xt/submit-tx node [[:put :xt_docs {:xt/id :doc1 :v 2} {:for-valid-time [:from #inst "2020"]}]
                                  [:put :xt_docs {:xt/id :doc2 :v 2} {:for-valid-time [:from #inst "2100"]}]
                                  [:delete :xt_docs :doc3]])]

      ;; valid-time
      (t/is (= {{:v 1, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table xt_docs, :for-valid-time [:at #inst "2017"], :for-system-time nil}
                                           [xt/id v]]
                                         {:node node}))))

      (t/is (= {{:v 1, :xt/id :doc2} 1 {:v 2, :xt/id :doc1} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table xt_docs, :for-valid-time [:at :now], :for-system-time nil}
                                           [xt/id v]]
                                         {:node node}))))
      ;; system-time
      (t/is (= {{:v 1, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1 {:v 1, :xt/id :doc3} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table xt_docs, :for-valid-time [:at :now], :for-system-time nil}
                                           [xt/id v]]
                                         {:node node :basis {:tx tx1}}))))

      (t/is (= {{:v 1, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table xt_docs, :for-valid-time [:at #inst "2017"], :for-system-time nil}
                                           [xt/id v]]
                                         {:node node :basis {:tx tx1}}))))

      (t/is (= {{:v 2, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table xt_docs, :for-valid-time [:at :now], :for-system-time nil}
                                           [xt/id v]]
                                         {:node node :basis {:tx tx2}}))))

      (t/is (= {{:v 2, :xt/id :doc1} 1 {:v 2, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table xt_docs, :for-valid-time [:at #inst "2100"], :for-system-time nil}
                                           [xt/id v]]
                                         {:node node :basis {:tx tx2}})))))))

(t/deftest test-past-point-point-queries-with-valid-time
  (with-open [node (node/start-node tu/*node-opts*)]
    (let [tx1 (xt/submit-tx node [[:put :xt_docs {:xt/id :doc1 :v 1} {:for-valid-time [:from #inst "2015"]}]
                                  [:put :xt_docs {:xt/id :doc2 :v 1} {:for-valid-time [:from #inst "2015"]}]
                                  [:put :xt_docs {:xt/id :doc3 :v 1} {:for-valid-time [:from #inst "2018"]}]])

          tx2 (xt/submit-tx node [[:put :xt_docs {:xt/id :doc1 :v 2} {:for-valid-time [:from #inst "2020"]}]
                                  [:put :xt_docs {:xt/id :doc2 :v 2} {:for-valid-time [:from #inst "2100"]}]
                                  [:delete :xt_docs :doc3]])]

      ;; valid-time
      (t/is (= #{{:v 1, :xt/id :doc1,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "2020-01-01T00:00Z[UTC]"}
                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "2100-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table xt_docs, :for-valid-time [:at #inst "2017"]}
                                   [xt/id v xt/valid-from xt/valid-to]]
                                 {:node node}))))

      (t/is (= #{{:v 2, :xt/id :doc1
                  :xt/valid-from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}

                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "2100-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table xt_docs, :for-valid-time [:at #inst "2023"]}
                                   [xt/id v xt/valid-from xt/valid-to]]
                                 {:node node}))))
      ;; system-time
      (t/is (= #{{:v 1, :xt/id :doc1,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
                 {:v 1, :xt/id :doc3,
                  :xt/valid-from #time/zoned-date-time "2018-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table xt_docs, :for-valid-time [:at #inst "2023"]}
                                   [xt/id v xt/valid-from xt/valid-to]]
                                 {:node node :basis {:tx tx1}}))))

      (t/is (= #{{:v 1, :xt/id :doc1,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table xt_docs, :for-valid-time [:at #inst "2017"]}
                                   [xt/id v xt/valid-from xt/valid-to]]
                                 {:node node :basis {:tx tx1}}))))

      (t/is (= #{{:v 2, :xt/id :doc1,
                  :xt/valid-from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
                 {:v 1, :xt/id :doc2
                  :xt/valid-from #time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "2100-01-01T00:00Z[UTC]",}}
               (set (tu/query-ra '[:scan
                                   {:table xt_docs, :for-valid-time [:at #inst "2023"]}
                                   [xt/id v xt/valid-from xt/valid-to]]
                                 {:node node :basis {:tx tx2}}))))

      (t/is (= #{{:v 2, :xt/id :doc1,
                  :xt/valid-from #time/zoned-date-time "2020-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
                 {:v 2, :xt/id :doc2,
                  :xt/valid-from #time/zoned-date-time "2100-01-01T00:00Z[UTC]",
                  :xt/valid-to #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table xt_docs, :for-valid-time [:at #inst "2100"]}
                                   [xt/id v xt/valid-from xt/valid-to]]
                                 {:node node :basis {:tx tx2}})))))))

(t/deftest test-scanning-temporal-cols
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id :doc}
                         {:for-valid-time [:in #inst "2021" #inst "3000"]}]])

    (let [res (first (tu/query-ra '[:scan {:table xt_docs}
                                    [xt/id
                                     xt/valid-from xt/valid-to
                                     xt/system-from xt/system-to]]
                                  {:node node}))]
      (t/is (= #{:xt/id :xt/valid-from :xt/valid-to :xt/system-to :xt/system-from}
               (-> res keys set)))

      (t/is (= {:xt/id :doc, :xt/valid-from (util/->zdt #inst "2021"), :xt/valid-to (util/->zdt #inst "3000")}
               (dissoc res :xt/system-from :xt/system-to))))

    (t/is (= {:xt/id :doc, :app-time-start (util/->zdt #inst "2021"), :app-time-end (util/->zdt #inst "3000")}
             (-> (first (tu/query-ra '[:project [xt/id
                                                 {app-time-start xt/valid-from}
                                                 {app-time-end xt/valid-to}]
                                       [:scan {:table xt_docs}
                                        [xt/id xt/valid-from xt/valid-to]]]
                                     {:node node}))
                 (dissoc :xt/system-from :xt/system-to))))))

(t/deftest test-only-scanning-temporal-cols-45
  (with-open [node (node/start-node {})]
    (let [{tt :system-time} (xt/submit-tx node [[:put :xt_docs {:xt/id :doc}]])]

      (t/is (= #{{:xt/valid-from (util/->zdt tt)
                  :xt/valid-to (util/->zdt util/end-of-time)
                  :xt/system-from (util/->zdt tt),
                  :xt/system-to (util/->zdt util/end-of-time)}}
               (set (tu/query-ra '[:scan {:table xt_docs}
                                   [xt/valid-from xt/valid-to
                                    xt/system-from xt/system-to]]
                                 {:node node})))))))

(t/deftest test-aligns-temporal-columns-correctly-363
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :foo {:xt/id :my-doc, :last_updated "tx1"}]] {:system-time #inst "3000"})

    (xt/submit-tx node [[:put :foo {:xt/id :my-doc, :last_updated "tx2"}]] {:system-time #inst "3001"})

    (tu/finish-chunk! node)

    (t/is (= #{{:last_updated "tx2",
                :xt/valid-from #time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/valid-to
                #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]",
                :xt/system-from #time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/system-to
                #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
               {:last_updated "tx1",
                :xt/valid-from #time/zoned-date-time "3000-01-01T00:00Z[UTC]",
                :xt/valid-to #time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/system-from #time/zoned-date-time "3000-01-01T00:00Z[UTC]",
                :xt/system-to
                #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]"}
               {:last_updated "tx1",
                :xt/valid-from #time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/valid-to
                #time/zoned-date-time "9999-12-31T23:59:59.999999Z[UTC]",
                :xt/system-from #time/zoned-date-time "3000-01-01T00:00Z[UTC]",
                :xt/system-to #time/zoned-date-time "3001-01-01T00:00Z[UTC]"}}
             (set (tu/query-ra '[:scan {:table foo, :for-system-time :all-time}
                                 [{xt/system-from (< xt/system-from #time/zoned-date-time "3002-01-01T00:00Z")}
                                  {xt/system-to (> xt/system-to #time/zoned-date-time "2999-01-01T00:00Z")}
                                  xt/valid-from
                                  xt/valid-to
                                  last_updated]]
                               {:node node :default-all-valid-time? true}))))))

(t/deftest test-for-valid-time-in-params
  (let [tt1 (util/->zdt #inst "2020-01-01")
        tt2 (util/->zdt #inst "2020-01-02")
        eot (util/->zdt util/end-of-time)]
    (with-open [node (node/start-node {})]
      (xt/submit-tx node [[:put :foo {:xt/id 1, :version "version 1" :last_updated "tx1"}
                           {:app-time-start tt1 :app-time-end eot}]])

      (xt/submit-tx node [[:put :foo {:xt/id 2, :version "version 2" :last_updated "tx2"}
                           {:app-time-start tt2 :app-time-end eot}]])
      (t/is (= #{{:xt/id 1, :version "version 1"} {:xt/id 2, :version "version 2"}}
               (set (tu/query-ra '[:scan {:table foo,
                                          :for-valid-time [:between ?_start ?_end]}
                                   [xt/id version]]
                                 {:node node :params {'?_start (util/->instant tt1)
                                                      '?_end (util/->instant eot)}}))))
      (t/is (= #{{:xt/id 1, :version "version 1"} {:xt/id 2, :version "version 2"}}
               (set
                (tu/query-ra '[:scan {:table foo,
                                      :for-valid-time :all-time}
                               [xt/id version]]
                             {:node node :params {'?_start (util/->instant tt1)
                                                  '?_end (util/->instant eot)}})))))))

(t/deftest test-scan-col-types
  (with-open [node (node/start-node {})]
    (let [^IRaQuerySource ra-src (util/component node :xtdb.operator/ra-query-source)]
      (letfn [(->col-types [tx]
                (-> (.prepareRaQuery ra-src '[:scan {:table xt_docs} [xt/id]])
                    (.bind (util/component node :xtdb/indexer) {:node node, :basis {:tx tx}})
                    (.columnTypes)))]

        (let [tx (-> (xt/submit-tx node [[:put :xt_docs {:xt/id :doc}]])
                     (tu/then-await-tx node))]
          (tu/finish-chunk! node)

          (t/is (= '{xt/id :keyword}
                   (->col-types tx))))

        (let [tx (-> (xt/submit-tx node [[:put :xt_docs {:xt/id "foo"}]])
                     (tu/then-await-tx node))]

          (t/is (= '{xt/id [:union #{:keyword :utf8}]}
                   (->col-types tx))))))))

#_ ; TODO adapt for scan/->temporal-range
(t/deftest can-create-temporal-min-max-range
  (let [μs-2018 (util/instant->micros (util/->instant #inst "2018"))
        μs-2019 (util/instant->micros (util/->instant #inst "2019"))]
    (letfn [(transpose [[mins maxs]]
              (->> (map vector mins maxs)
                   (zipmap [:sys-end :xt/id :sys-start :row-id :app-time-start :app-time-end])
                   (into {} (remove (comp #{[Long/MIN_VALUE Long/MAX_VALUE]} val)))))]
      (t/is (= {:app-time-start [Long/MIN_VALUE μs-2019]
                :app-time-end [(inc μs-2019) Long/MAX_VALUE]}
               (transpose (scan/->temporal-min-max-range
                           nil nil nil
                           {'xt/valid-from '(<= xt/valid-from #inst "2019")
                            'xt/valid-to '(> xt/valid-to #inst "2019")}))))

      (t/is (= {:app-time-start [μs-2019 μs-2019]}
               (transpose (scan/->temporal-min-max-range
                           nil nil nil
                           {'xt/valid-from '(= xt/valid-from #inst "2019")}))))

      (t/testing "symbol column name"
        (t/is (= {:app-time-start [μs-2019 μs-2019]}
                 (transpose (scan/->temporal-min-max-range
                             nil nil nil
                             {'xt/valid-from '(= xt/valid-from #inst "2019")})))))

      (t/testing "conjunction"
        (t/is (= {:app-time-start [Long/MIN_VALUE μs-2019]}
                 (transpose (scan/->temporal-min-max-range
                             nil nil nil
                             {'xt/valid-from '(and (<= xt/valid-from #inst "2019")
                                                   (<= xt/valid-from #inst "2020"))})))))

      (t/testing "disjunction not supported"
        (t/is (= {}
                 (transpose (scan/->temporal-min-max-range
                             nil nil nil
                             {'xt/valid-from '(or (= xt/valid-from #inst "2019")
                                                  (= xt/valid-from #inst "2020"))})))))

      (t/testing "ignores non-ts literals"
        (t/is (= {:app-time-start [μs-2019 μs-2019]}
                 (transpose (scan/->temporal-min-max-range
                             nil nil nil
                             {'xt/valid-from '(and (= xt/valid-from #inst "2019")
                                                   (= xt/valid-from nil))})))))

      (t/testing "parameters"
        (t/is (= {:app-time-start [μs-2018 Long/MAX_VALUE]
                  :app-time-end [Long/MIN_VALUE (dec μs-2018)]
                  :sys-start [Long/MIN_VALUE μs-2019]
                  :sys-end [(inc μs-2019) Long/MAX_VALUE]}
                 (with-open [params (tu/open-params {'?system-time (util/->instant #inst "2019")
                                                     '?app-time (util/->instant #inst "2018")})]
                   (transpose (scan/->temporal-min-max-range
                               params nil nil
                               {'xt/system-from '(>= ?system-time xt/system-from)
                                'xt/system-to '(< ?system-time xt/system-to)
                                'xt/valid-from '(<= ?app-time xt/valid-from)
                                'xt/valid-to '(> ?app-time xt/valid-to)})))))))))

(t/deftest test-content-pred
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id :ivan, :first-name "Ivan", :last-name "Ivanov"}]
                        [:put :xt_docs {:xt/id :petr, :first-name "Petr", :last-name "Petrov"}]])
    (t/is (= [{:first-name "Ivan", :xt/id :ivan}]
             (tu/query-ra '[:scan
                            {:table xt_docs,  :for-valid-time nil, :for-system-time nil}
                            [{first-name (= first-name "Ivan")} xt/id]]
                          {:node node})))))

(t/deftest test-absent-columns
  (with-open [node (node/start-node {})]
    (xt/submit-tx node [[:put :xt_docs {:xt/id :foo, :col1 "foo1"}]
                        [:put :xt_docs {:xt/id :bar, :col1 "bar1", :col2 "bar2"}]])


    ;; column not existent in all docs
    (t/is (= [{:col2 "bar2", :xt/id :bar}]
             (tu/query-ra '[:scan {:table xt_docs} [xt/id {col2 (= col2 "bar2")}]]
                          {:node node})))

    ;; column not existent at all
    (t/is (= []
             (tu/query-ra
              '[:scan {:table xt_docs} [xt/id {col-x (= col-x "toto")}]]
              {:node node})))))

(t/deftest test-iid-fast-path
  (let [before-uuid #uuid "00000000-0000-0000-0000-000000000000"
        search-uuid #uuid "80000000-0000-0000-0000-000000000000"
        after-uuid #uuid "f0000000-0000-0000-0000-000000000000"]
    (with-open [node (node/start-node {})]
      (xt/submit-tx node [[:put :xt-docs {:xt/id before-uuid :version 1}]
                          [:put :xt-docs {:xt/id search-uuid :version 1}]
                          [:put :xt-docs {:xt/id after-uuid :version 1}]])
      (xt/submit-tx node [[:put :xt-docs {:xt/id search-uuid :version 2}]])

      (t/is (nil? (scan/selects->iid-byte-buffer {} vw/empty-params)))

      (t/is (= (util/uuid->byte-buffer search-uuid)
               (scan/selects->iid-byte-buffer {"xt/id" (list '= 'xt/id search-uuid)} vw/empty-params)))

      (t/is (nil? (scan/selects->iid-byte-buffer {"xt/id" (list '< 'xt/id search-uuid)} vw/empty-params)))

      (with-open [^RelationReader params-rel (vw/open-params tu/*allocator* {'?search-uuid #uuid "80000000-0000-0000-0000-000000000000"})]
        (t/is (= (util/uuid->byte-buffer search-uuid)
                 (scan/selects->iid-byte-buffer '{"xt/id" (= xt/id ?search-uuid)}
                                                params-rel))))

      (with-open [search-uuid-vec (vw/open-vec tu/*allocator* (name '?search-uuid) [#uuid "00000000-0000-0000-0000-000000000000"
                                                                                    #uuid "80000000-0000-0000-0000-000000000000"])
                  ^RelationReader params-rel (vw/open-rel [search-uuid-vec])]
        (t/is (nil? (scan/selects->iid-byte-buffer '{xt/id (= xt/id ?search-uuid)}
                                                   params-rel))))

      (let [old-select->iid-byte-buffer scan/selects->iid-byte-buffer]
        (with-redefs [scan/selects->iid-byte-buffer
                      (fn [& args]
                        (let [iid-pred (apply old-select->iid-byte-buffer args)]
                          (assert iid-pred "iid-pred can't be nil")
                          iid-pred))]

          (t/is (= [{:version 2, :xt/id search-uuid}]
                   (tu/query-ra [:scan {:table 'xt_docs} ['version {'xt/id (list '= 'xt/id search-uuid)}]]
                                {:node node})))

          (t/is (= [{:version 2, :xt/id search-uuid}]
                   (tu/query-ra [:scan {:table 'xt_docs} ['version {'xt/id (list '=  search-uuid 'xt/id)}]]
                                {:node node})))

          (t/is (= [{:version 2, :xt/id search-uuid}]
                   (tu/query-ra '[:scan {:table xt_docs} [version {xt/id (= xt/id ?search-uuid)}]]
                                {:node node :params {'?search-uuid #uuid "80000000-0000-0000-0000-000000000000"}})))

          (t/is (= [{:version 2, :xt/id search-uuid}
                    {:version 1, :xt/id search-uuid}]
                   (tu/query-ra [:scan {:table 'xt_docs
                                        :for-valid-time :all-time}
                                 ['version {'xt/id (list '= 'xt/id search-uuid)}]]
                                {:node node}))))))))

(t/deftest test-iid-fast-path-chunk-boundary
  (let [before-uuid #uuid "00000000-0000-0000-0000-000000000000"
        search-uuid #uuid "80000000-0000-0000-0000-000000000000"
        after-uuid #uuid "f0000000-0000-0000-0000-000000000000"
        uuids [before-uuid search-uuid after-uuid]
        !search-uuid-versions (atom [])]
    (with-open [node (node/start-node {:xtdb/indexer {:rows-per-chunk 20 :page-limit 16}})]
      (->> (for [i (range 110)]
             (let [uuid (rand-nth uuids)]
               (when (= uuid search-uuid)
                 (swap! !search-uuid-versions conj i))
               [[:put :xt_docs {:xt/id uuid :version i}]]))
           (mapv #(xt/submit-tx node %)))

      (t/is (= [{:version (last @!search-uuid-versions), :xt/id search-uuid}]
               (tu/query-ra [:scan {:table 'xt_docs} ['version {'xt/id (list '= 'xt/id search-uuid)}]]
                            {:node node})))

      (t/is (=  (into #{} @!search-uuid-versions)
                (->> (tu/query-ra [:scan {:table 'xt_docs
                                          :for-valid-time :all-time}
                                   ['version {'xt/id (list '= 'xt/id search-uuid)}]]
                                  {:node node})
                     (map :version)
                     set))))))

(deftest test-iid-fast-path-multiple-pages
  (with-open [node (node/start-node {:xtdb.indexer/live-index {:page-limit 16}})]
    (let [uuids (tu/uuid-seq 40)
          search-uuid (rand-nth uuids)]
      (xt/submit-tx node (for [uuid (take 20 uuids)] [:put :xt_docs {:xt/id uuid}]))
      (tu/finish-chunk! node)
      (xt/submit-tx node (for [uuid (drop 20 uuids)] [:put :xt_docs {:xt/id uuid}]))
      (tu/finish-chunk! node)

      (t/is (= [{:xt/id search-uuid}]
               (tu/query-ra [:scan '{:table xt_docs} [{'xt/id (list '= 'xt/id search-uuid)}]]
                            {:node node}))))))

(t/deftest test-iid-selector
  (let [before-uuid #uuid "00000000-0000-0000-0000-000000000000"
        search-uuid #uuid "80000000-0000-0000-0000-000000000000"
        after-uuid #uuid "f0000000-0000-0000-0000-000000000000"
        ^IRelationSelector iid-selector (scan/iid-selector (util/uuid->byte-buffer search-uuid))]
    (letfn [(test-uuids [uuids]
              (with-open [rel-wrt (vw/->rel-writer tu/*allocator*)]
                (let [iid-wtr (.writerForName rel-wrt "xt$iid" [:fixed-size-binary 16])]
                  (doseq [uuid uuids]
                    (.writeBytes iid-wtr (util/uuid->byte-buffer uuid))))
                (.select iid-selector tu/*allocator* (vw/rel-wtr->rdr rel-wrt) nil)))]

      (t/is (= nil
               (seq (test-uuids [])))
            "empty relation")

      (t/is (= nil
               (seq (test-uuids [before-uuid before-uuid])))
            "only \"smaller\" uuids")

      (t/is (= nil
               (seq (test-uuids [after-uuid after-uuid])))
            "only \"larger\" uuids")

      (t/is (= [1 2]
               (seq (test-uuids [before-uuid search-uuid search-uuid])))
            "smaller uuids and no larger ones")

      (t/is (= [0 1]
               (seq (test-uuids [search-uuid search-uuid after-uuid])))
            "no smaller uuids but larger ones")

      (t/is (= [1 2]
               (seq (test-uuids [before-uuid search-uuid search-uuid after-uuid])))
            "general case"))))

(deftest test-live-tries-with-multiple-leaves-are-loaded-correctly-2710
  (with-open [node (node/start-node {:xtdb.indexer/live-index {:page-limit 16}})]
    (-> (xt/submit-tx node (for [i (range 20)] [:put :xt_docs {:xt/id i}]))
        (tu/then-await-tx node))

    (t/is (= (into #{} (map #(hash-map :xt/id %)) (range 20))
             (set (tu/query-ra '[:scan {:table xt_docs} [xt/id]]
                               {:node node}))))))

(deftest test-pushdown-blooms
  (xt/submit-tx tu/*node* [[:put :xt-docs {:xt/id :foo, :col "foo"}]
                           [:put :xt-docs {:xt/id :bar, :col "bar"}]])
  (tu/finish-chunk! tu/*node*)
  (xt/submit-tx tu/*node* [[:put :xt-docs {:xt/id :toto, :col "toto"}]])
  (tu/finish-chunk! tu/*node*)

  (let [!page-idxs-cnt (atom 0)
        old-filter-trie-match scan/filter-pushdown-bloom-page-idx-pred]
    (with-redefs [scan/filter-pushdown-bloom-page-idx-pred (fn [& args]
                                                             (when-let [^IntPredicate pred (apply old-filter-trie-match args)]
                                                               (reify IntPredicate
                                                                 (test [_ page-idx]
                                                                   (let [res (.test pred page-idx)]
                                                                     (when res (swap! !page-idxs-cnt inc))
                                                                     res)))))]
      (t/is (= [{:col "toto"}]
               (tu/query-ra
                '[:join [{col col}]
                  [:scan {:table xt_docs} [col {col (= col "toto")}]]
                  [:scan {:table xt_docs} [col]]]
                {:node tu/*node*})))
      ;; one page for the right side
      (t/is (= 1 @!page-idxs-cnt)))))
