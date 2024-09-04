(ns xtdb.operator.scan-test
  (:require [clojure.java.io :as io]
            [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.compactor :as c]
            [xtdb.node :as xtn]
            [xtdb.operator.scan :as scan]
            xtdb.query
            [xtdb.test-util :as tu]
            [xtdb.time :as time]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import (java.util.function IntPredicate)
           org.apache.arrow.vector.types.pojo.Schema
           org.apache.arrow.vector.VectorSchemaRoot
           xtdb.operator.SelectionSpec
           xtdb.vector.RelationReader))

(t/use-fixtures :each tu/with-mock-clock tu/with-allocator tu/with-node)

(t/deftest test-simple-scan
  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :foo, :col1 "foo1"}]
                        [:put-docs :xt_docs {:xt/id :bar, :col1 "bar1", :col2 "bar2"}]
                        [:put-docs :xt_docs {:xt/id :foo, :col2 "baz2"}]])

    (t/is (= #{{:xt/id :bar, :col1 "bar1", :col2 "bar2"}
               {:xt/id :foo, :col2 "baz2"}}
             (set (tu/query-ra '[:scan {:table public/xt_docs} [_id col1 col2]]
                               {:node node}))))))

(t/deftest test-simple-scan-with-namespaced-attributes
  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :foo, :the-ns/col1 "foo1"}]
                        [:put-docs :xt_docs {:xt/id :bar, :the-ns/col1 "bar1", :col2 "bar2"}]
                        [:put-docs :xt_docs {:xt/id :foo, :the-ns/col2 "baz2"}]])

    (t/is (= #{{:xt/id :bar, :the-ns/col1 "bar1", :col2 "bar2"}
               {:xt/id :foo}}
             (set (tu/query-ra '[:scan {:table public/xt_docs} [_id the_ns$col1 col2]]
                               {:node node}))))))

(t/deftest test-duplicates-in-scan-1
  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :foo}]])

    (t/is (= [{:xt/id :foo}]
             (tu/query-ra '[:scan {:table public/xt_docs} [_id _id]]
                          {:node node})))))

(t/deftest test-chunk-boundary
  (with-open [node (xtn/start-node {:indexer {:rows-per-chunk 20}})]
    (->> (for [i (range 110)]
           [:put-docs :xt_docs {:xt/id i}])
         (partition-all 10)
         (mapv #(xt/submit-tx node %)))

    (t/is (= (set (for [i (range 110)] {:xt/id i}))
             (set (tu/query-ra '[:scan {:table public/xt_docs} [_id]]
                               {:node node}))))))

(t/deftest test-chunk-boundary-different-struct-types
  (with-open [node (xtn/start-node {:indexer {:rows-per-chunk 20}})]
    (xt/submit-tx node (for [i (range 20)]
                         [:put-docs :xt_docs {:xt/id i :foo {:bar 42}}]))

    (xt/submit-tx node (for [i (range 20 40)]
                         [:put-docs :xt_docs {:xt/id i :foo {:bar "forty-two"}}]))

    (t/is (= {{:bar 42} 20, {:bar "forty-two"} 20}
             (frequencies (tu/query-ra
                           '[:project [{bar (. foo :bar)}]
                             [:scan {:table public/xt_docs} [foo]]]
                           {:node node}))))

    (c/compact-all! node #xt.time/duration "PT1S")

    (t/is (= {{:bar 42} 20, {:bar "forty-two"} 20}
             (frequencies (tu/query-ra
                           '[:project [{bar (. foo :bar)}]
                             [:scan {:table public/xt_docs} [foo]]]
                           {:node node}))))))

(t/deftest test-row-copying-different-struct-types-between-chunk-boundaries-3338
  (with-open [node (xtn/start-node {:indexer {:rows-per-chunk 20}})]
    (xt/submit-tx node (for [i (range 20)]
                         [:put-docs :xt_docs {:xt/id i :foo {:bar 42}}]))

    (xt/submit-tx node (for [i (range 20 40)]
                         [:put-docs :xt_docs {:xt/id i :foo {:bar "forty-two"}}]))

    (t/is (= #{{:foo {:bar 42}} {:foo {:bar "forty-two"}}}
             (set (tu/query-ra
                   ;; the cross-join copies data from the underlying IndirectMultiVectorReader
                   '[:apply :cross-join {}
                     [:table [{}]]
                     [:scan {:table public/xt_docs} [foo]]]
                   {:node node}))))))

(t/deftest test-smaller-page-limit
  (with-open [node (xtn/start-node {:indexer {:page-limit 16}})]
    (xt/submit-tx node (for [i (range 20)] [:put-docs :xt_docs {:xt/id i}]))

    (tu/finish-chunk! node)

    (t/is (= (into #{} (map #(hash-map :xt/id %)) (range 20))
             (set (tu/query-ra '[:scan {:table public/xt_docs} [_id]]
                               {:node node}))))))

(t/deftest test-metadata
  (with-open [node (xtn/start-node {:indexer {:rows-per-chunk 20}})]
    (->> (for [i (range 100)]
           [:put-docs :xt_docs {:xt/id i}])
         (partition-all 20)
         (mapv #(xt/submit-tx node %)))

    (t/is (= (set (concat (for [i (range 20)] {:xt/id i}) (for [i (range 80 100)] {:xt/id i})))
             (set (tu/query-ra '[:scan {:table public/xt_docs} [{_id (or (< _id 20)
                                                                  (>= _id 80))}]]
                               {:node node})))
          "testing only getting some trie matches"))

  (with-open [node (xtn/start-node {:indexer {:rows-per-chunk 20}})]
    (xt/submit-tx node (for [i (range 20)] [:put-docs :xt_docs {:xt/id i}]))
    (xt/submit-tx node (for [i (range 20)] [:delete-docs :xt_docs i]))

    (t/is (= []
             (tu/query-ra '[:scan {:table public/xt_docs} [{_id (< _id 20)}]]
                          {:node node}))
          "testing newer chunks relevant even if not matching")

    (t/is (= []
             (tu/query-ra '[:scan {:table public/xt_docs} [toto]]
                          {:node node}))
          "testing nothing matches"))

  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :xt_docs {:xt/id 1 :boolean-or-int true}]
                        [:put-docs :xt_docs {:xt/id 2 :boolean-or-int 2}]])
    (tu/finish-chunk! node)

    (t/is (= [{:boolean-or-int true}]
             (tu/query-ra '[:scan {:table public/xt_docs} [{boolean_or_int (= boolean_or_int true)}]]
                          {:node node}))
          "testing boolean metadata")))

(t/deftest test-past-point-point-queries
  (with-open [node (xtn/start-node {})]
    (let [tx1 (xt/submit-tx node [[:put-docs {:into :xt_docs, :valid-from #inst "2015"}
                                   {:xt/id :doc1 :v 1}]
                                  [:put-docs {:into :xt_docs, :valid-from #inst "2015"}
                                   {:xt/id :doc2 :v 1}]
                                  [:put-docs {:into :xt_docs, :valid-from #inst "2018"}
                                   {:xt/id :doc3 :v 1}]])

          tx2 (xt/submit-tx node [[:put-docs {:into :xt_docs, :valid-from #inst "2020"}
                                   {:xt/id :doc1 :v 2}]
                                  [:put-docs {:into :xt_docs, :valid-from #inst "2100"}
                                   {:xt/id :doc2 :v 2}]
                                  [:delete-docs :xt_docs :doc3]])]

      ;; valid-time
      (t/is (= {{:v 1, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table public/xt_docs, :for-valid-time [:at #inst "2017"], :for-system-time nil}
                                           [_id v]]
                                         {:node node}))))

      (t/is (= {{:v 1, :xt/id :doc2} 1 {:v 2, :xt/id :doc1} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table public/xt_docs, :for-valid-time [:at :now], :for-system-time nil}
                                           [_id v]]
                                         {:node node}))))

      ;; system-time
      (t/is (= {{:v 1, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1 {:v 1, :xt/id :doc3} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table public/xt_docs, :for-valid-time [:at :now], :for-system-time nil}
                                           [_id v]]
                                         {:node node :basis {:at-tx tx1}}))))

      (t/is (= {{:v 1, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table public/xt_docs, :for-valid-time [:at #inst "2017"], :for-system-time nil}
                                           [_id v]]
                                         {:node node :basis {:at-tx tx1}}))))

      (t/is (= {{:v 2, :xt/id :doc1} 1 {:v 1, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table public/xt_docs, :for-valid-time [:at :now], :for-system-time nil}
                                           [_id v]]
                                         {:node node :basis {:at-tx tx2}}))))

      (t/is (= {{:v 2, :xt/id :doc1} 1 {:v 2, :xt/id :doc2} 1}
               (frequencies (tu/query-ra '[:scan
                                           {:table public/xt_docs, :for-valid-time [:at #inst "2100"], :for-system-time nil}
                                           [_id v]]
                                         {:node node :basis {:at-tx tx2}})))))))

(t/deftest test-past-point-point-queries-with-valid-time
  (with-open [node (xtn/start-node tu/*node-opts*)]
    (let [tx1 (xt/submit-tx node [[:put-docs {:into :xt_docs, :valid-from #inst "2015"}
                                   {:xt/id :doc1 :v 1}]
                                  [:put-docs {:into :xt_docs, :valid-from #inst "2015"}
                                   {:xt/id :doc2 :v 1}]
                                  [:put-docs {:into :xt_docs, :valid-from #inst "2018"}
                                   {:xt/id :doc3 :v 1}]])

          tx2 (xt/submit-tx node [[:put-docs {:into :xt_docs, :valid-from #inst "2020"}
                                   {:xt/id :doc1 :v 2}]
                                  [:put-docs {:into :xt_docs, :valid-from #inst "2100"}
                                   {:xt/id :doc2 :v 2}]
                                  [:delete-docs :xt_docs :doc3]])]

      ;; valid-time
      (t/is (= #{{:v 1, :xt/id :doc1,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #xt.time/zoned-date-time "2020-01-01T00:00Z[UTC]"}
                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #xt.time/zoned-date-time "2100-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table public/xt_docs, :for-valid-time [:at #inst "2017"]}
                                   [_id v _valid_from _valid_to]]
                                 {:node node}))))

      (t/is (= #{{:v 2, :xt/id :doc1
                  :xt/valid-from #xt.time/zoned-date-time "2020-01-01T00:00Z[UTC]",}

                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #xt.time/zoned-date-time "2100-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table public/xt_docs, :for-valid-time [:at #inst "2023"]}
                                   [_id v _valid_from _valid_to]]
                                 {:node node}))))
      ;; system-time
      (t/is (= #{{:v 1, :xt/id :doc1,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]"}
                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]"}
                 {:v 1, :xt/id :doc3,
                  :xt/valid-from #xt.time/zoned-date-time "2018-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table public/xt_docs, :for-valid-time [:at #inst "2023"]}
                                   [_id v _valid_from _valid_to]]
                                 {:node node :basis {:at-tx tx1}}))))

      (t/is (= #{{:v 1, :xt/id :doc1,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]"}
                 {:v 1, :xt/id :doc2,
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table public/xt_docs, :for-valid-time [:at #inst "2017"]}
                                   [_id v _valid_from _valid_to]]
                                 {:node node :basis {:at-tx tx1}}))))

      (t/is (= #{{:v 2, :xt/id :doc1,
                  :xt/valid-from #xt.time/zoned-date-time "2020-01-01T00:00Z[UTC]"}
                 {:v 1, :xt/id :doc2
                  :xt/valid-from #xt.time/zoned-date-time "2015-01-01T00:00Z[UTC]",
                  :xt/valid-to #xt.time/zoned-date-time "2100-01-01T00:00Z[UTC]",}}
               (set (tu/query-ra '[:scan
                                   {:table public/xt_docs, :for-valid-time [:at #inst "2023"]}
                                   [_id v _valid_from _valid_to]]
                                 {:node node :basis {:at-tx tx2}}))))

      (t/is (= #{{:v 2, :xt/id :doc1,
                  :xt/valid-from #xt.time/zoned-date-time "2020-01-01T00:00Z[UTC]"}
                 {:v 2, :xt/id :doc2,
                  :xt/valid-from #xt.time/zoned-date-time "2100-01-01T00:00Z[UTC]"}}
               (set (tu/query-ra '[:scan
                                   {:table public/xt_docs, :for-valid-time [:at #inst "2100"]}
                                   [_id v _valid_from _valid_to]]
                                 {:node node :basis {:at-tx tx2}})))))))

(t/deftest test-scanning-temporal-cols
  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs {:into :xt_docs, :valid-from #inst "2021", :valid-to #inst "3000"}
                         {:xt/id :doc}]])

    (let [res (first (tu/query-ra '[:scan {:table public/xt_docs}
                                    [_id
                                     _valid_from _valid_to
                                     _system_from _system_to]]
                                  {:node node}))]
      (t/is (= #{:xt/id :xt/valid-from :xt/valid-to :xt/system-from}
               (-> res keys set)))

      (t/is (= {:xt/id :doc, :xt/valid-from (time/->zdt #inst "2021"), :xt/valid-to (time/->zdt #inst "3000")}
               (dissoc res :xt/system-from :xt/system-to))))

    (t/is (= {:xt/id :doc, :app-time-start (time/->zdt #inst "2021"), :app-time-end (time/->zdt #inst "3000")}
             (-> (first (tu/query-ra '[:project [_id
                                                 {app_time_start _valid_from}
                                                 {app_time_end _valid_to}]
                                       [:scan {:table public/xt_docs}
                                        [_id _valid_from _valid_to]]]
                                     {:node node}))
                 (dissoc :xt/system-from :xt/system-to))))))

(t/deftest test-only-scanning-temporal-cols-45
  (util/with-open [node (xtn/start-node {})]
    (let [tx (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :doc}]])
          tt (.getSystemTime tx)]

      (t/is (= #{{:xt/valid-from (time/->zdt tt)
                  :xt/system-from (time/->zdt tt)}}
               (set (tu/query-ra '[:scan {:table public/xt_docs}
                                   [_valid_from _valid_to
                                    _system_from _system_to]]
                                 {:node node})))))))

(t/deftest test-aligns-temporal-columns-correctly-363
  (util/with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :foo {:xt/id :my-doc, :last_updated "tx1"}]] {:system-time #inst "3000"})

    (xt/submit-tx node [[:put-docs :foo {:xt/id :my-doc, :last_updated "tx2"}]] {:system-time #inst "3001"})

    (tu/finish-chunk! node)

    (t/is (= #{{:last-updated "tx2",
                :xt/valid-from #xt.time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/system-from #xt.time/zoned-date-time "3001-01-01T00:00Z[UTC]"}
               {:last-updated "tx1",
                :xt/valid-from #xt.time/zoned-date-time "3000-01-01T00:00Z[UTC]",
                :xt/valid-to #xt.time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/system-from #xt.time/zoned-date-time "3000-01-01T00:00Z[UTC]"}
               {:last-updated "tx1",
                :xt/valid-from #xt.time/zoned-date-time "3001-01-01T00:00Z[UTC]",
                :xt/system-from #xt.time/zoned-date-time "3000-01-01T00:00Z[UTC]",
                :xt/system-to #xt.time/zoned-date-time "3001-01-01T00:00Z[UTC]"}}
             (set (tu/query-ra '[:scan {:table public/foo,
                                        :for-system-time [:between #xt.time/zoned-date-time "2999-01-01T00:00Z" #xt.time/zoned-date-time "3002-01-01T00:00Z"]
                                        :for-valid-time :all-time}
                                 [_system_from _system_to
                                  _valid_from _valid_to
                                  last_updated]]
                               {:node node}))))))

(t/deftest test-for-valid-time-in-params
  (let [tt1 (time/->zdt #inst "2020-01-01")
        tt2 (time/->zdt #inst "2020-01-02")]
    (with-open [node (xtn/start-node {})]
      (xt/submit-tx node [[:put-docs {:into :foo, :valid-from tt1}
                           {:xt/id 1, :version "version 1" :last_updated "tx1"}]])

      (xt/submit-tx node [[:put-docs {:into :foo, :valid-from tt2}
                           {:xt/id 2, :version "version 2" :last_updated "tx2"}]])
      (t/is (= #{{:xt/id 1, :version "version 1"} {:xt/id 2, :version "version 2"}}
               (set (tu/query-ra '[:scan {:table public/foo,
                                          :for-valid-time [:between ?_start ?_end]}
                                   [_id version]]
                                 {:node node :params {'?_start (time/->instant tt1)
                                                      '?_end nil}}))))
      (t/is (= #{{:xt/id 1, :version "version 1"} {:xt/id 2, :version "version 2"}}
               (set
                (tu/query-ra '[:scan {:table public/foo,
                                      :for-valid-time :all-time}
                               [_id version]]
                             {:node node :params {'?_start (time/->instant tt1)
                                                  '?_end nil}})))))))

(t/deftest test-scan-col-types
  (with-open [node (xtn/start-node {})]
    (letfn [(->col-type [col]
              (:col-types
               (tu/query-ra [:scan '{:table public/xt_docs} [col]]
                            {:node node, :with-col-types? true})))]

      (-> (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :doc}]])
          (tu/then-await-tx node))

      (tu/finish-chunk! node)

      (t/is (= '{_id :keyword}
               (->col-type '_id)))

      (xt/submit-tx node [[:put-docs :xt_docs {:xt/id "foo"}]])

      (t/is (= '{_id [:union #{:keyword :utf8}]}
               (->col-type '_id))))))

#_ ; TODO adapt for scan/->temporal-bounds
(t/deftest can-create-temporal-min-max-range
  (let [μs-2018 (time/instant->micros (time/->instant #inst "2018"))
        μs-2019 (time/instant->micros (time/->instant #inst "2019"))]
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
                 (with-open [params (tu/open-params {'?system-time (time/->instant #inst "2019")
                                                     '?app-time (time/->instant #inst "2018")})]
                   (transpose (scan/->temporal-min-max-range
                               params nil nil
                               {'xt/system-from '(>= ?system-time xt/system-from)
                                'xt/system-to '(< ?system-time xt/system-to)
                                'xt/valid-from '(<= ?app-time xt/valid-from)
                                'xt/valid-to '(> ?app-time xt/valid-to)})))))))))

(t/deftest test-content-pred
  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :ivan, :first-name "Ivan", :last-name "Ivanov"}]
                        [:put-docs :xt_docs {:xt/id :petr, :first-name "Petr", :last-name "Petrov"}]])
    (t/is (= [{:first-name "Ivan", :xt/id :ivan}]
             (tu/query-ra '[:scan
                            {:table public/xt_docs,  :for-valid-time nil, :for-system-time nil}
                            [{first_name (= first_name "Ivan")} _id]]
                          {:node node})))))

(t/deftest test-absent-columns
  (with-open [node (xtn/start-node {})]
    (xt/submit-tx node [[:put-docs :xt_docs {:xt/id :foo, :col1 "foo1"}]
                        [:put-docs :xt_docs {:xt/id :bar, :col1 "bar1", :col2 "bar2"}]])


    ;; column not existent in all docs
    (t/is (= [{:col2 "bar2", :xt/id :bar}]
             (tu/query-ra '[:scan {:table public/xt_docs} [_id {col2 (= col2 "bar2")}]]
                          {:node node})))

    ;; column not existent at all
    (t/is (= []
             (tu/query-ra
              '[:scan {:table public/xt_docs} [_id {col-x (= col-x "toto")}]]
              {:node node})))))

(t/deftest test-iid-fast-path
  (let [before-uuid #uuid "00000000-0000-0000-0000-000000000000"
        search-uuid #uuid "80000000-0000-0000-0000-000000000000"
        after-uuid #uuid "f0000000-0000-0000-0000-000000000000"]
    (with-open [node (xtn/start-node {})]
      (xt/submit-tx node [[:put-docs :xt-docs {:xt/id before-uuid :version 1}]
                          [:put-docs :xt-docs {:xt/id search-uuid :version 1}]
                          [:put-docs :xt-docs {:xt/id after-uuid :version 1}]])
      (xt/submit-tx node [[:put-docs :xt-docs {:xt/id search-uuid :version 2}]])

      (t/is (nil? (scan/selects->iid-byte-buffer {} vw/empty-params)))

      (t/is (= (util/uuid->byte-buffer search-uuid)
               (scan/selects->iid-byte-buffer {"_id" (list '= '_id search-uuid)} vw/empty-params)))

      (t/is (nil? (scan/selects->iid-byte-buffer {"_id" (list '< '_id search-uuid)} vw/empty-params)))

      (with-open [^RelationReader params-rel (vw/open-params tu/*allocator* {'?search-uuid #uuid "80000000-0000-0000-0000-000000000000"})]
        (t/is (= (util/uuid->byte-buffer search-uuid)
                 (scan/selects->iid-byte-buffer '{"_id" (= _id ?search-uuid)}
                                                params-rel))))

      (with-open [search-uuid-vec (vw/open-vec tu/*allocator* (name '?search-uuid) [#uuid "00000000-0000-0000-0000-000000000000"
                                                                                    #uuid "80000000-0000-0000-0000-000000000000"])
                  ^RelationReader params-rel (vw/open-rel [search-uuid-vec])]
        (t/is (nil? (scan/selects->iid-byte-buffer '{_id (= _id ?search-uuid)}
                                                   params-rel))))

      (let [old-select->iid-byte-buffer scan/selects->iid-byte-buffer]
        (with-redefs [scan/selects->iid-byte-buffer
                      (fn [& args]
                        (let [iid-pred (apply old-select->iid-byte-buffer args)]
                          (assert iid-pred "iid-pred can't be nil")
                          iid-pred))]

          (t/is (= [{:version 2, :xt/id search-uuid}]
                   (tu/query-ra [:scan {:table 'public/xt_docs} ['version {'_id (list '= '_id search-uuid)}]]
                                {:node node})))

          (t/is (= [{:version 2, :xt/id search-uuid}]
                   (tu/query-ra [:scan {:table 'public/xt_docs} ['version {'_id (list '=  search-uuid '_id)}]]
                                {:node node})))

          (t/is (= [{:version 2, :xt/id search-uuid}]
                   (tu/query-ra '[:scan {:table public/xt_docs} [version {_id (= _id ?search-uuid)}]]
                                {:node node :params {'?search-uuid #uuid "80000000-0000-0000-0000-000000000000"}})))

          (t/is (= [{:version 2, :xt/id search-uuid}
                    {:version 1, :xt/id search-uuid}]
                   (tu/query-ra [:scan {:table 'public/xt_docs
                                        :for-valid-time :all-time}
                                 ['version {'_id (list '= '_id search-uuid)}]]
                                {:node node}))))))))

(t/deftest test-iid-fast-path-chunk-boundary
  (let [before-uuid #uuid "00000000-0000-0000-0000-000000000000"
        search-uuid #uuid "80000000-0000-0000-0000-000000000000"
        after-uuid #uuid "f0000000-0000-0000-0000-000000000000"
        uuids [before-uuid search-uuid after-uuid]
        !search-uuid-versions (atom [])]
    (with-open [node (xtn/start-node {:indexer {:rows-per-chunk 20 :page-limit 16}})]
      (->> (for [i (range 110)]
             (let [uuid (rand-nth uuids)]
               (when (= uuid search-uuid)
                 (swap! !search-uuid-versions conj i))
               [[:put-docs :xt_docs {:xt/id uuid :version i}]]))
           (mapv #(xt/submit-tx node %)))

      (t/is (= [{:version (last @!search-uuid-versions), :xt/id search-uuid}]
               (tu/query-ra [:scan {:table 'public/xt_docs} ['version {'_id (list '= '_id search-uuid)}]]
                            {:node node})))

      (t/is (=  (into #{} @!search-uuid-versions)
                (->> (tu/query-ra [:scan {:table 'public/xt_docs
                                          :for-valid-time :all-time}
                                   ['version {'_id (list '= '_id search-uuid)}]]
                                  {:node node})
                     (map :version)
                     set))))))

(deftest test-iid-fast-path-multiple-pages
  (with-open [node (xtn/start-node {:indexer {:page-limit 16}})]
    (let [uuids (tu/uuid-seq 40)
          search-uuid (rand-nth uuids)]
      (xt/submit-tx node (for [uuid (take 20 uuids)] [:put-docs :xt_docs {:xt/id uuid}]))
      (tu/finish-chunk! node)
      (xt/submit-tx node (for [uuid (drop 20 uuids)] [:put-docs :xt_docs {:xt/id uuid}]))
      (tu/finish-chunk! node)

      (t/is (= [{:xt/id search-uuid}]
               (tu/query-ra [:scan '{:table public/xt_docs} [{'_id (list '= '_id search-uuid)}]]
                            {:node node}))))))

(t/deftest test-iid-selector
  (let [before-uuid #uuid "00000000-0000-0000-0000-000000000000"
        search-uuid #uuid "80000000-0000-0000-0000-000000000000"
        after-uuid #uuid "f0000000-0000-0000-0000-000000000000"
        ^SelectionSpec iid-selector (scan/iid-selector (util/uuid->byte-buffer search-uuid))]
    (letfn [(test-uuids [uuids]
              (with-open [rel-wrt (-> (VectorSchemaRoot/create (Schema. [(types/->field "_iid" #xt.arrow/type [:fixed-size-binary 16] false)])
                                                               tu/*allocator*)
                                      (vw/root->writer))]
                (let [iid-wtr (.colWriter rel-wrt "_iid")]
                  (doseq [uuid uuids]
                    (.writeBytes iid-wtr (util/uuid->byte-buffer uuid))))
                (.select iid-selector tu/*allocator* (vw/rel-wtr->rdr rel-wrt) {} nil)))]

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
  (with-open [node (xtn/start-node {:indexer {:page-limit 16}})]
    (-> (xt/submit-tx node (for [i (range 20)] [:put-docs :xt_docs {:xt/id i}]))
        (tu/then-await-tx node))

    (t/is (= (into #{} (map #(hash-map :xt/id %)) (range 20))
             (set (tu/query-ra '[:scan {:table public/xt_docs} [_id]]
                               {:node node}))))))

(deftest test-pushdown-blooms
  (xt/submit-tx tu/*node* [[:put-docs :xt-docs {:xt/id :foo, :col "foo"}]
                           [:put-docs :xt-docs {:xt/id :bar, :col "bar"}]])
  (tu/finish-chunk! tu/*node*)
  (xt/submit-tx tu/*node* [[:put-docs :xt-docs {:xt/id :toto, :col "toto"}]])
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
                  [:scan {:table public/xt_docs} [col {col (= col "toto")}]]
                  [:scan {:table public/xt_docs} [col]]]
                {:node tu/*node*})))
      ;; one page for the right side
      (t/is (= 1 @!page-idxs-cnt)))))

(deftest duplicate-rows-2815
  (let [page-limit 16]
    (with-open [node (xtn/start-node {:indexer {:page-limit page-limit}})]
      (let [first-page (for [i (range page-limit)] (java.util.UUID. 0 i))
            second-page (for [i (range page-limit)] (java.util.UUID. 1 i))
            uuid (first first-page)]
        (xt/submit-tx node (for [uuid (concat first-page second-page)]
                             [:put-docs :docs {:xt/id uuid}]))
        (tu/then-await-tx node)
        (tu/finish-chunk! node)
        (xt/submit-tx node [[:put-docs :docs {:xt/id uuid}]])
        (tu/then-await-tx node)

        ;; first + second page
        (t/is (= 32
                 (count (tu/query-ra
                         '[:scan {:table public/docs} [xt/id]]
                         {:node node}))))

        (t/is (= (into #{} (concat first-page second-page))
                 (into #{} (map :xt/id) (tu/query-ra
                                         '[:scan {:table public/docs} [_id]]
                                         {:node node}))))))))

(deftest dont-use-fast-path-on-non-literals-2768
  (xt/submit-tx tu/*node* [[:put-docs :xt-docs {:xt/id "foo-start" :v "foo-start"}]
                           [:put-docs :xt-docs {:xt/id "bar-start" :v "bar-start"}]])

  (t/is (= [#:xt{:id "foo-start"}]
           (tu/query-ra
            '[:scan
              {:table public/xt_docs} [{_id (= "foo" (substring _id 1 3))}]]
            {:node tu/*node*}))))

(t/deftest test-iid-col-type-3016
  (xt/submit-tx tu/*node* [[:put-docs :comments {:xt/id 1}]])

  (t/is (= {'_iid [:fixed-size-binary 16]
            '_valid_from types/temporal-col-type
            '_valid_to types/nullable-temporal-type}

           (:col-types (tu/query-ra '[:scan {:table public/comments}
                                      [_iid _valid_from _valid_to]]
                                    {:node tu/*node*
                                     :with-col-types? true})))))

(deftest live-hash-trie-branches-get-expanded-3247
  (xt/submit-tx tu/*node* (for [id (range 2000)] ; 2000 to go over the page size
                            [:put-docs :docs {:xt/id id}]))

  (t/is (= 2000 (count (xt/q tu/*node* '(from :docs [{:xt/id id}]))))))

(deftest test-leaves-are-in-system-order
  (binding [c/*page-size* 1]
    (with-open [node (xtn/start-node {:log [:in-memory {:instant-src (tu/->mock-clock (tu/->instants :year))}]})]

      (dotimes [i 2]
        (xt/execute-tx node [[:put-docs :docs {:xt/id 1 :version i}]]))
      (tu/finish-chunk! node)
      (c/compact-all! node #xt.time/duration "PT2S")

      (t/is (= [{:xt/id 1,
                 :xt/valid-to #xt.time/zoned-date-time "2021-01-01T00:00Z[UTC]"}]
               (xt/q node '(from :docs {:bind [xt/id xt/valid-to {:version 0}]
                                        :for-valid-time :all-time})))))))

(deftest test-recency-filtering
  (binding [c/*page-size* 1
            c/*l1-file-size-rows* 256
            c/*ignore-signal-block?* true]
    (with-open [node (xtn/start-node {:log [:in-memory {:instant-src (tu/->mock-clock (tu/->instants :year))}]})]
      ;; 2020 - 2025
      (let [tx-key (last (for [i (range 6)]
                           (xt/submit-tx node [[:put-docs :docs {:xt/id 1 :col i}]])))]


        (tu/finish-chunk! node)
        ;; compaction happens in 2026
        (c/compact-all! node)

        (let [query-opts {:node node
                          :basis {:at-tx tx-key :current-time (:system-time tx-key)}}]

          ;; no filter, we should still get the latest entry (2025)
          (t/is (= [{:xt/id 1,
                     :xt/valid-from #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                   (tu/query-ra '[:scan {:table public/docs} [_id _valid_from]] query-opts)))

          ;; one day earlier we still get the 2024 entry
          (t/is (= [{:xt/id 1,
                     :xt/valid-from #xt.time/zoned-date-time "2024-01-01T00:00Z[UTC]",
                     :xt/valid-to #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                   (tu/query-ra '[:scan {:table public/docs} [_id _valid_from _valid_to]]
                                (update-in query-opts [:basis :current-time] (constantly #xt.time/instant "2024-12-30T00:00:00Z")))))

          ;; two entries 2024 and 2025
          (t/is (= [{:xt/id 1,
                     :xt/valid-from #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}
                    {:xt/id 1,
                     :xt/valid-from #xt.time/zoned-date-time "2024-01-01T00:00Z[UTC]",
                     :xt/valid-to #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                   (tu/query-ra '[:scan {:table public/docs :for-valid-time [:between #inst "2024" #inst "2026"]}
                                  [_id _valid_from _valid_to]]
                                query-opts)))


          ;; newest entry, basis at 2025
          (t/is (= [{:xt/id 1,
                     :xt/valid-from #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                   (tu/query-ra '[:scan {:table public/docs :for-valid-time [:between #inst "2026" nil]}
                                  [_id _valid_from _valid_to]]
                                query-opts)))
          ;; everything
          (t/is (= (repeat 6 {:xt/id 1})
                   (tu/query-ra '[:scan {:table public/docs :for-valid-time :all-time} [_id]] query-opts)))

          (t/is (= (repeat 11 {:xt/id 1})
                   (tu/query-ra '[:scan {:table public/docs
                                         :for-valid-time :all-time
                                         :for-system-time :all-time}
                                  [_id]] query-opts))))))))

(deftest test-range-query-earlier-filtering
  (binding [c/*page-size* 1
            c/*l1-file-size-rows* 256
            c/*ignore-signal-block?* true]
    (let [insts (tu/->instants :year)]
      (with-open [node (xtn/start-node {:log [:in-memory {:instant-src (tu/->mock-clock insts)}]})]
        ;; versions 2020 - 2025
        (let [tx-key (last (for [[i [start end]] (->> (partition 2 1 insts)
                                                      (take 6)
                                                      (map-indexed vector))]
                             (xt/execute-tx node [[:put-docs {:into :docs
                                                              :valid-from start
                                                              :valid-to end}

                                                   {:xt/id 1 :version i}]])))]


          (tu/finish-chunk! node)
          ;; compaction happens in 2026
          (c/compact-all! node #xt.time/duration "PT2S")

          (let [query-opts {:node node
                            :basis {:at-tx tx-key :current-time (:system-time tx-key)}}]

            ;; at the end of 2024 we still get the 2024
            (t/is (= [{:xt/id 1,
                       :xt/valid-from #xt.time/zoned-date-time "2024-01-01T00:00Z[UTC]",
                       :xt/valid-to #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                     (tu/query-ra '[:scan {:table public/docs} [_id _valid_from _valid_to]]
                                  (update-in query-opts [:basis :current-time] (constantly #xt.time/instant "2024-12-30T00:00:00Z")))))
            ;; two entries 2024 and 2025
            (t/is (= [{:xt/id 1,
                       :xt/valid-from #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"
                       :xt/valid-to #xt.time/zoned-date-time "2026-01-01T00:00Z[UTC]"}
                      {:xt/id 1,
                       :xt/valid-from #xt.time/zoned-date-time "2024-01-01T00:00Z[UTC]",
                       :xt/valid-to #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                     (tu/query-ra '[:scan {:table public/docs :for-valid-time [:between #inst "2024" #inst "2026"]}
                                    [_id _valid_from _valid_to]]
                                  query-opts)))


            ;; newest entry, basis at 2025
            (t/is (= [{:xt/id 1,
                       :xt/valid-from #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"
                       :xt/valid-to #xt.time/zoned-date-time "2026-01-01T00:00Z[UTC]"}]
                     (tu/query-ra '[:scan {:table public/docs :for-valid-time [:between #inst "2025-12-30" nil]}
                                    [_id _valid_from _valid_to]]
                                  query-opts)))

            ;; everything
            (t/is (= (repeat 6 {:xt/id 1})
                     (tu/query-ra '[:scan {:table public/docs
                                           :for-valid-time :all-time
                                           :for-system-time :all-time} [_id]] query-opts)))

            (t/testing "adding something to the live-index"
              (let [;; versions 2026 and 2027
                    tx-key2 (last (for [[i [start end]] (->> (partition 2 1 insts)
                                                             (drop 6)
                                                             (take 2)
                                                             (map-indexed vector))]
                                    (xt/execute-tx node [[:put-docs {:into :docs
                                                                     :valid-from start}
                                                                     ;; :valid-to end

                                                          {:xt/id 1 :version (+ i 6)}]])))
                    ;; versions 2120 and 2121
                    tx-key3 (last (for [[i [start end]] (->> (partition 2 1 insts)
                                                             (drop 100)
                                                             (take 2)
                                                             (map-indexed vector))]
                                    (xt/execute-tx node [[:put-docs {:into :docs
                                                                     :valid-from start
                                                                     :valid-to end}
                                                          {:xt/id 1 :version (+ i 8)}]])))
                    query-opts {:node node
                                :basis {:at-tx tx-key2 :current-time (:system-time tx-key)}}]

                (t/testing "temporal bounds from the live-index"
                  (t/is (= [{:xt/id 1,
                             :xt/valid-from #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"
                             :xt/valid-to #xt.time/zoned-date-time "2026-01-01T00:00Z[UTC]"}
                            {:xt/id 1,
                             :xt/valid-from #xt.time/zoned-date-time "2024-01-01T00:00Z[UTC]",
                             :xt/valid-to #xt.time/zoned-date-time "2025-01-01T00:00Z[UTC]"}]
                           (tu/query-ra '[:scan {:table public/docs :for-valid-time [:in #inst "2024" #inst "2026"]}
                                          [_id _valid_from _valid_to]]
                                        query-opts))))

                (t/testing "earlier system times get ignored via the basis"
                  (t/is (= [#:xt{:valid-from #xt.time/zoned-date-time "2027-01-01T00:00Z[UTC]",
                                 :id 1}
                            #:xt{:valid-to #xt.time/zoned-date-time "2027-01-01T00:00Z[UTC]",
                                 :valid-from #xt.time/zoned-date-time "2026-01-01T00:00Z[UTC]",
                                 :id 1}]

                           (tu/query-ra '[:scan {:table public/docs :for-valid-time [:between #inst "2026" #inst "3000"]}
                                          [_id _valid_from _valid_to]]
                                        query-opts))))

                (t/testing "earlier system times bound the interval even when laying outside"
                  (t/is (= [#:xt{:valid-to #xt.time/zoned-date-time "2120-01-01T00:00Z[UTC]",
                                 :valid-from #xt.time/zoned-date-time "2027-01-01T00:00Z[UTC]",
                                 :id 1}
                            #:xt{:valid-to #xt.time/zoned-date-time "2027-01-01T00:00Z[UTC]",
                                 :valid-from #xt.time/zoned-date-time "2026-01-01T00:00Z[UTC]",
                                 :id 1}]
                           (tu/query-ra '[:scan {:table public/docs :for-valid-time [:between #inst "2026" #inst "2027"]}
                                          [_id _valid_from _valid_to]]
                                        (update-in query-opts [:basis :at-tx] (constantly tx-key3))))))))))))))
