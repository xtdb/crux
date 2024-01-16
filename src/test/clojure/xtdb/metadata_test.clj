(ns xtdb.metadata-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.expression.metadata :as expr.meta]
            [xtdb.metadata :as meta]
            [xtdb.node :as xtn]
            [xtdb.test-util :as tu]
            [xtdb.trie :as trie]
            [xtdb.util :as util])
  (:import (clojure.lang MapEntry)
           xtdb.IBufferPool
           (xtdb.metadata IMetadataManager)))

(t/use-fixtures :each tu/with-node)
(t/use-fixtures :once tu/with-allocator)

(t/deftest test-param-metadata-error-310
  (let [tx1 (xt/submit-tx tu/*node*
                          [(-> (xt/sql-op "INSERT INTO users (xt$id, name, xt$valid_from) VALUES (?, ?, ?)")
                               (xt/with-op-args
                                 ["dave", "Dave", #inst "2018"]
                                 ["claire", "Claire", #inst "2019"]))])]

    (t/is (= [{:name "Dave"}]
             (xt/q tu/*node* "SELECT users.name FROM users WHERE users.xt$id = ?"
                   {:args ["dave"]
                    :basis {:at-tx tx1}}))
          "#310")))

(deftest test-bloom-filter-for-num-types-2133
  (let [tx (-> (xt/submit-tx tu/*node* [(xt/put :xt_docs {:num 0 :xt/id "a"})
                                        (xt/put :xt_docs {:num 1 :xt/id "b"})
                                        (xt/put :xt_docs {:num 1.0 :xt/id "c"})
                                        (xt/put :xt_docs {:num 4 :xt/id "d"})
                                        (xt/put :xt_docs {:num (short 3) :xt/id "e"})
                                        (xt/put :xt_docs {:num 2.0 :xt/id "f"})])
               (tu/then-await-tx tu/*node*))]

    (tu/finish-chunk! tu/*node*)

    (t/is (= [{:num 1} {:num 1.0}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{num (= num 1)}]]
                          {:node tu/*node* :basis {:at-tx tx}})))

    (t/is (= [{:num 2.0}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{num (= num 2)}]]
                          {:node tu/*node* :basis {:at-tx tx}})))

    (t/is (= [{:num 4}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{num (= num ?x)}]]
                          {:node tu/*node* :basis {:at-tx tx} :params {'?x (byte 4)}})))

    (t/is (= [{:num 3}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{num (= num ?x)}]]
                          {:node tu/*node* :basis {:at-tx tx} :params {'?x (float 3)}})))))

(deftest test-bloom-filter-for-datetime-types-2133
  (let [tx (-> (xt/submit-tx tu/*node* [(xt/put :xt_docs {:timestamp #time/date "2010-01-01" :xt/id "a"})
                                        (xt/put :xt_docs {:timestamp #time/zoned-date-time "2010-01-01T00:00:00Z" :xt/id "b"})
                                        (xt/put :xt_docs {:timestamp #time/date-time "2010-01-01T00:00:00" :xt/id "c"})
                                        (xt/put :xt_docs {:timestamp #time/date "2020-01-01" :xt/id "d"})]
                             {:default-tz #time/zone "Z"})
               (tu/then-await-tx tu/*node*))]

    (tu/finish-chunk! tu/*node*)

    (t/is (= [{:timestamp #time/date "2010-01-01"}
              {:timestamp #time/zoned-date-time "2010-01-01T00:00Z"}
              {:timestamp #time/date-time "2010-01-01T00:00:00"}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{timestamp (= timestamp #time/zoned-date-time "2010-01-01T00:00:00Z")}]]
                          {:node tu/*node* :basis {:at-tx tx} :default-tz #time/zone "Z"})))

    (t/is (= [{:timestamp #time/date "2010-01-01"}
              {:timestamp #time/zoned-date-time "2010-01-01T00:00Z"}
              {:timestamp #time/date-time "2010-01-01T00:00:00"}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{timestamp (= timestamp ?x)}]]
                          {:node tu/*node* :basis {:at-tx tx}
                           :default-tz  #time/zone "Z" :params {'?x #time/date "2010-01-01"}})))

    (t/is (= [{:timestamp #time/date "2010-01-01"}
              {:timestamp #time/zoned-date-time "2010-01-01T00:00Z"}
              {:timestamp #time/date-time "2010-01-01T00:00:00"}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{timestamp (= timestamp #time/date-time "2010-01-01T00:00:00")}]]
                          {:node tu/*node* :basis {:at-tx tx} :default-tz #time/zone "Z"})))))

(deftest test-bloom-filter-for-time-types
  (let [tx (-> (xt/submit-tx tu/*node* [(xt/put :xt_docs {:time #time/time "01:02:03" :xt/id "a"})
                                        (xt/put :xt_docs {:time #time/time "04:05:06" :xt/id "b"})]
                             {:default-tz #time/zone "Z"})
               (tu/then-await-tx tu/*node*))]

    (tu/finish-chunk! tu/*node*)

    (t/is (= [{:time #time/time "04:05:06"}]
             (tu/query-ra '[:scan {:table xt_docs}
                            [{time (= time #time/time "04:05:06")}]]
                          {:node tu/*node* :basis {:at-tx tx} :default-tz #time/zone "Z"})))))

(deftest test-min-max-on-xt-id
  (with-open [node (xtn/start-node {:indexer {:page-limit 16}})]
    (-> (xt/submit-tx node (for [i (range 20)] (xt/put :xt_docs {:xt/id i})))
        (tu/then-await-tx node))

    (tu/finish-chunk! node)

    (let [^IBufferPool buffer-pool (tu/component node :xtdb/buffer-pool)
          first-buckets (map (comp first tu/byte-buffer->path trie/->iid) (range 20))
          bucket->page-idx (->> (into (sorted-set) first-buckets)
                                (map-indexed #(MapEntry/create %2 %1))
                                (into {}))
          min-max-by-bucket (-> (group-by :bucket (map-indexed (fn [index bucket] {:index index :bucket bucket}) first-buckets))
                                (update-vals #(reduce (fn [res {:keys [index]}]
                                                        (-> res
                                                            (update :min min index)
                                                            (update :max max index)))
                                                      {:min Long/MAX_VALUE :max Long/MIN_VALUE}
                                                      %)))

          relevant-pages (->> (filter (fn [[_ {:keys [min max]}]] (<= min 10 max)) min-max-by-bucket)
                              (map (comp bucket->page-idx first)))

          ^IMetadataManager metadata-mgr (tu/component node ::meta/metadata-manager)
          literal-selector (expr.meta/->metadata-selector '(and (< xt/id 11) (> xt/id 9)) '{xt/id :i64} {})
          meta-file-path (trie/->table-meta-file-path (util/->path "tables/xt_docs") (trie/->log-trie-key 0 0 21))]
      (util/with-open [{meta-rel-rdr :rdr} (trie/open-meta-file buffer-pool meta-file-path)]
        (let [table-metadata (.tableMetadata metadata-mgr meta-rel-rdr meta-file-path)
              page-idx-pred (.build literal-selector table-metadata)]

          (t/is (= #{"xt$iid" "xt$id" "xt$system_from"}
                   (.columnNames table-metadata)))

          (doseq [page-idx relevant-pages]
            (t/is (true? (.test page-idx-pred page-idx)))))))))

(t/deftest test-boolean-metadata
  (xt/submit-tx tu/*node* [(xt/put :xt_docs {:xt/id 1 :boolean-or-int true})])
  (tu/finish-chunk! tu/*node*)

  (let [^IMetadataManager metadata-mgr (tu/component tu/*node* ::meta/metadata-manager)
        ^IBufferPool buffer-pool (tu/component tu/*node* :xtdb/buffer-pool)
        true-selector (expr.meta/->metadata-selector '(= boolean-or-int true) '{boolean-or-int :bool} {})
        meta-file-path (trie/->table-meta-file-path (util/->path "tables/xt_docs") (trie/->log-trie-key 0 0 2))]

    (util/with-open [{meta-rel-rdr :rdr} (trie/open-meta-file buffer-pool meta-file-path)]
      (let [table-metadata (.tableMetadata metadata-mgr meta-rel-rdr meta-file-path)
            page-idx-pred (.build true-selector table-metadata)]
        (t/is (= #{"xt$iid" "xt$id" "xt$system_from" "boolean_or_int"}
                 (.columnNames table-metadata)))

        (t/is (true? (.test page-idx-pred 0)))))))
