(ns xtdb.fixtures.tpch
  (:require [clojure.edn :as edn]
            [clojure.instant :as i]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.fixtures :as fix :refer [*api*]]
            [xtdb.query :as q])
  (:import (io.airlift.tpch GenerateUtils TpchColumn TpchColumnType$Base TpchEntity TpchTable)
           (java.util Date)))

(def tpch-column-types->xtdb-calcite-type
  {TpchColumnType$Base/INTEGER :bigint
   TpchColumnType$Base/VARCHAR :varchar
   TpchColumnType$Base/IDENTIFIER :varchar
   TpchColumnType$Base/DOUBLE :double
   TpchColumnType$Base/DATE :timestamp})

(defn tpch-table->xtdb-sql-schema [^TpchTable t]
  {:xt/id (keyword "tpch" (.getTableName t))
   :xtdb.sql/table-name (.getTableName t)
   :xtdb.sql/table-query {:find (vec (for [^TpchColumn c (.getColumns t)]
                                       (symbol (.getColumnName c))))
                          :where (vec (for [^TpchColumn c (.getColumns t)]
                                        ['e (keyword (.getColumnName c)) (symbol (.getColumnName c))]))}
   :xtdb.sql/table-columns (->> (for [^TpchColumn c (.getColumns t)]
                                  [(symbol (.getColumnName c)) (tpch-column-types->xtdb-calcite-type (.getBase (.getType c)))])
                                (into {}))})

(defn tpch-tables->xtdb-sql-schemas []
  (map tpch-table->xtdb-sql-schema (TpchTable/getTables)))

(def table->pkey
  {"part" [:p_partkey]
   "supplier" [:s_suppkey]
   "partsupp" [:ps_partkey :ps_suppkey]
   "customer" [:c_custkey]
   "lineitem" [:l_orderkey :l_linenumber]
   "orders" [:o_orderkey]
   "nation" [:n_nationkey]
   "region" [:r_regionkey]})

(defn tpch-entity->doc [^TpchTable t ^TpchEntity b]
  (let [doc (->> (for [^TpchColumn c (.getColumns t)]
                   [(keyword (.getColumnName c))
                    (condp = (.getBase (.getType c))
                      TpchColumnType$Base/IDENTIFIER
                      (str (str/replace (.getColumnName c) #".+_" "") "_" (.getIdentifier c b))
                      TpchColumnType$Base/INTEGER
                      (long (.getInteger c b))
                      TpchColumnType$Base/VARCHAR
                      (.getString c b)
                      TpchColumnType$Base/DOUBLE
                      (.getDouble c b)
                      TpchColumnType$Base/DATE
                      (i/read-instant-date (GenerateUtils/formatDate (.getDate c b))))])
                 (into {}))
        pkey-columns (get table->pkey (.getTableName t))
        pkey (mapv doc pkey-columns)]
    (assoc doc :xt/id (str/join "___" pkey))))

(def default-scale-factor 0.05)

;; 0.05 = 7500 customers, 75000 orders, 299814 lineitems, 10000 part, 40000 partsupp, 500 supplier, 25 nation, 5 region
(defn tpch-table->docs
  ([^TpchTable t]
   (tpch-table->docs t default-scale-factor))
  ([^TpchTable t sf]
   ;; first happens to be customers (;; 150000 for sf 0.05)
   (->> (seq (.createGenerator ^TpchTable t sf 1 1))
        (map (partial tpch-entity->doc t)))))

(defn with-tpch-schema [f]
  (fix/transact! *api* (tpch-tables->xtdb-sql-schemas))
  (f))

(defn submit-docs!
  ([node]
   (submit-docs! node default-scale-factor))
  ([node sf]
   (println "Transacting TPC-H tables...")
   (->> (for [^TpchTable t (TpchTable/getTables)]
          (let [[last-tx doc-count] (->> (tpch-table->docs t sf)
                                         (partition-all 1000)
                                         (reduce (fn [[_last-tx last-doc-count] chunk]
                                                   [(xt/submit-tx node (vec (for [doc chunk]
                                                                              [::xt/put doc])))
                                                    (+ last-doc-count (count chunk))])
                                                 [nil 0]))]
            (println "Transacted" doc-count (.getTableName t))
            last-tx))
        last)))

(defn load-docs! [node & args]
  (xt/await-tx node (apply submit-docs! node args)))

(defn- with-in-args [q in-args]
 (-> q (vary-meta assoc ::in-args in-args)))

(defn run-query [db q]
  (apply xt/q db q (::in-args (meta q))))

(defn query-plan-for [db q]
  (q/query-plan-for db q (::in-args (meta q))))

;; NOTE: timings below are hot/cold, on my machine (Ryzen 7 5800X, 16GB RAM)
;; SF 0.05, against commit `d4437676`, 2022-02-04
;; they're not particularly scientifically measured, so worth not paying too much attention
;; to the exact times - they're more intended to show the relative times of each of the queries,
;; to spot queries that might benefit from future optimisation

;; "Elapsed time: 15839.21097 msecs"
;; "Elapsed time: 6047.218633 msecs"
(def q1
  '{:find [l_returnflag
           l_linestatus
           (sum l_quantity)
           (sum l_extendedprice)
           (sum (* l_extendedprice (- 1 l_discount)))
           (sum (* (* l_extendedprice (- 1 l_discount))
                   (+ 1 l_tax)))
           (avg l_quantity)
           (avg l_extendedprice)
           (avg l_discount)
           (count l)]
    :where [[l :l_shipdate l_shipdate]
            [l :l_quantity l_quantity]
            [l :l_extendedprice l_extendedprice]
            [l :l_discount l_discount]
            [l :l_tax l_tax]
            [l :l_returnflag l_returnflag]
            [l :l_linestatus l_linestatus]
            [(<= l_shipdate #inst "1998-09-02")]]
    :order-by [[l_returnflag :asc]
               [l_linestatus :asc]]})

;; "Elapsed time: 291.474365 msecs"
;; "Elapsed time: 98.729042 msecs"
(def q2
  '{:find [s_acctbal
           s_name
           n_name
           p
           p_mfgr
           s_address
           s_phone
           s_comment]
    :where [[p :p_mfgr p_mfgr]
            [p :p_size 15]
            [p :p_type p_type]
            [(re-find #"^.*BRASS$" p_type)]
            [ps :ps_partkey p]
            [ps :ps_supplycost ps_supplycost]
            [(q {:find [(min ps_supplycost)]
                 :in [$ p]
                 :where [[ps :ps_partkey p]
                         [ps :ps_supplycost ps_supplycost]
                         [ps :ps_suppkey s]
                         [s :s_nationkey n]
                         [n :n_regionkey r]
                         [r :r_name "EUROPE"]]} p) [[ps_supplycost]]]
            [ps :ps_suppkey s]
            [s :s_acctbal s_acctbal]
            [s :s_address s_address]
            [s :s_name s_name]
            [s :s_phone s_phone]
            [s :s_comment s_comment]
            [n :n_name n_name]
            [s :s_nationkey n]
            [n :n_regionkey r]
            [r :r_name "EUROPE"]]
    :order-by [[s_acctbal :desc]
               [n_name :asc]
               [s_name :asc]
               [p :asc]]
    :limit 100})

;; "Elapsed time: 1355.255634 msecs"
;; "Elapsed time: 764.763165 msecs"
(def q3
  (-> '{:find [o
               (sum (* l_extendedprice (- 1 l_discount)))
               o_orderdate
               o_shippriority]
        :in [?segment]
        :where [[c :c_mktsegment ?segment]
                [o :o_custkey c]
                [o :o_shippriority o_shippriority]
                [o :o_orderdate o_orderdate]
                [(< o_orderdate #inst "1995-03-15")]
                [l :l_orderkey o]
                [l :l_discount l_discount]
                [l :l_extendedprice l_extendedprice]
                [l :l_shipdate l_shipdate]
                [(> l_shipdate #inst "1995-03-15")]]
        :order-by [[(sum (* l_extendedprice (- 1 l_discount))) :desc]
                   [o_orderdate :asc]]
        :limit 10}
      (with-in-args ["BUILDING"])))

;; "Elapsed time: 621.653381 msecs"
;; "Elapsed time: 262.517773 msecs"
(def q4
  '{:find [o_orderpriority
           (count o)]
    :where [[o :o_orderdate o_orderdate]
            [o :o_orderpriority o_orderpriority]
            [(>= o_orderdate #inst "1993-07-01")]
            [(< o_orderdate #inst "1993-10-01")]
            (or-join [o]
                     (and [l :l_orderkey o]
                          [l :l_commitdate l_commitdate]
                          [l :l_receiptdate l_receiptdate]
                          [(< l_commitdate l_receiptdate)]))]
    :order-by [[o_orderpriority :asc]]})

;; "Elapsed time: 3365.050276 msecs"
;; "Elapsed time: 1927.300129 msecs"
(def q5
  (-> '{:find [n_name (sum (* l_extendedprice (- 1 l_discount)))]
        :in [?region]
        :where [[o :o_custkey c]
                [l :l_orderkey o]
                [l :l_suppkey s]
                [s :s_nationkey n]
                [c :c_nationkey n]
                [n :n_name n_name]
                [n :n_regionkey r]
                [r :r_name ?region]
                [l :l_extendedprice l_extendedprice]
                [l :l_discount l_discount]
                [o :o_orderdate o_orderdate]
                [(>= o_orderdate #inst "1994-01-01")]
                [(< o_orderdate #inst "1995-01-01")]]
        :order-by [[(sum (* l_extendedprice (- 1 l_discount))) :desc]]}
      (with-in-args ["ASIA"])))

;; "Elapsed time: 995.197119 msecs"
;; "Elapsed time: 963.57298 msecs"
(def q6
  '{:find [(sum (* l_extendedprice l_discount))]
    :where [[l :l_shipdate l_shipdate]
            [l :l_quantity l_quantity]
            [l :l_extendedprice l_extendedprice]
            [l :l_discount l_discount]
            [(>= l_shipdate #inst "1994-01-01")]
            [(< l_shipdate #inst "1995-01-01")]
            [(>= l_discount 0.05)]
            [(<= l_discount 0.07)]
            [(< l_quantity 24.0)]]})

(defn inst->year [^Date d]
  (+ 1900 (.getYear d)))

;; "Elapsed time: 7929.95054 msecs"
;; "Elapsed time: 6435.124485 msecs"
(def q7
  '{:find [supp_nation
           cust_nation
           l_year
           (sum (* l_extendedprice (- 1 l_discount)))]
    :where [[o :o_custkey c]
            [l :l_orderkey o]
            [l :l_suppkey s]
            [s :s_nationkey n1]
            [n1 :n_name supp_nation]
            [c :c_nationkey n2]
            [n2 :n_name cust_nation]
            (or (and [(= "FRANCE" supp_nation)]
                     [(= "GERMANY" cust_nation)])
                (and [(= "GERMANY" supp_nation)]
                     [(= "FRANCE" cust_nation)]))
            [l :l_shipdate l_shipdate]
            [l :l_discount l_discount]
            [l :l_extendedprice l_extendedprice]
            [(>= l_shipdate #inst "1995-01-01")]
            [(<= l_shipdate #inst "1996-12-31")]
            [(xtdb.fixtures.tpch/inst->year l_shipdate) l_year]]
    :order-by [[supp_nation :asc] [cust_nation :asc] [l_year :asc]]})

;; "Elapsed time: 163.938006 msecs"
;; "Elapsed time: 100.011933 msecs"
(def q8
  '{:find [o_year mkt_share]
    :where [[(q {:find [o_year
                        (sum (if (= "BRAZIL" nation) volume 0))
                        (sum volume)]
                 :where [[(q {:find [o_year (sum (* l_extendedprice (- 1 l_discount))) nation]
                              :where [[o :o_custkey c]
                                      [l :l_orderkey o]
                                      [l :l_suppkey s]
                                      [l :l_partkey p]
                                      [c :c_nationkey n1]
                                      [n1 :n_regionkey r1]
                                      [r1 :r_name "AMERICA"]
                                      [s :s_nationkey n2]
                                      [n2 :n_name nation]
                                      [l :l_discount l_discount]
                                      [l :l_extendedprice l_extendedprice]
                                      [o :o_orderdate o_orderdate]
                                      [(>= o_orderdate #inst "1995-01-01")]
                                      [(<= o_orderdate #inst "1996-12-31")]
                                      [p :p_type "ECONOMY ANODIZED STEEL"]
                                      [(xtdb.fixtures.tpch/inst->year o_orderdate) o_year]]})
                          [[o_year volume nation]]]]})
             [[o_year brazil_volume volume]]]
            [(/ brazil_volume volume) mkt_share]]
    :order-by [[o_year :asc]]})

;; "Elapsed time: 2580.94159 msecs"
;; "Elapsed time: 1639.428899 msecs"
(def q9
  '{:find [nation o_year
           (sum (- (* l_extendedprice (- 1 l_discount))
                   (* ps_supplycost l_quantity)))]
    :where [[l :l_orderkey o]
            [l :l_suppkey s]
            [l :l_partkey p]
            [ps :ps_partkey p]
            [ps :ps_suppkey s]
            [ps :ps_supplycost ps_supplycost]
            [s :s_nationkey n]
            [n :n_name nation]
            [p :p_name p_name]
            [(re-find #".*green.*" p_name)]
            [l :l_quantity l_quantity]
            [l :l_discount l_discount]
            [l :l_extendedprice l_extendedprice]
            [o :o_orderdate o_orderdate]
            [(xtdb.fixtures.tpch/inst->year o_orderdate) o_year]]
    :order-by [[nation :asc] [o_year :desc]]})

;; "Elapsed time: 2301.101635 msecs"
;; "Elapsed time: 1779.136327 msecs"
(def q10
  '{:find [c
           c_name
           (sum (* l_extendedprice (- 1 l_discount)))
           c_acctbal
           n_name
           c_address
           c_phone
           c_comment]
    :where [[o :o_custkey c]
            [l :l_orderkey o]
            [c :c_nationkey n]
            [n :n_name n_name]
            [c :c_name c_name]
            [c :c_acctbal c_acctbal]
            [c :c_address c_address]
            [c :c_phone c_phone]
            [c :c_comment c_comment]
            [l :l_extendedprice l_extendedprice]
            [l :l_discount l_discount]
            [o :o_orderdate o_orderdate]
            [(>= o_orderdate #inst "1993-10-01")]
            [(< o_orderdate #inst "1994-01-01")]
            [l :l_returnflag "R"]]
    :order-by [[(sum (* l_extendedprice (- 1 l_discount))) :desc]]
    :limit 20})

;; "Elapsed time: 144.718281 msecs"
;; "Elapsed time: 58.429466 msecs"
(def q11
  '{:find [ps_partkey value]
    :where [[(q {:find [(sum (* ps_supplycost ps_availqty))]
                 :where [[ps :ps_availqty ps_availqty]
                         [ps :ps_supplycost ps_supplycost]
                         [ps :ps_suppkey s]
                         [s :s_nationkey n]
                         [n :n_name "GERMANY"]]}) [[total-value]]]
            [(q {:find [ps_partkey
                        (sum (* ps_supplycost ps_availqty))]
                 :where [[ps :ps_availqty ps_availqty]
                         [ps :ps_supplycost ps_supplycost]
                         [ps :ps_partkey ps_partkey]
                         [ps :ps_suppkey s]
                         [s :s_nationkey n]
                         [n :n_name "GERMANY"]]}) [[ps_partkey value]]]
            [(* 0.0001 total-value) ret_2]
            [(> value ret_2)]]
    :order-by [[value :desc]]})

(def high-lines #{"1-URGENT" "2-HIGH"})

;; "Elapsed time: 2657.564311 msecs"
;; "Elapsed time: 1269.297801 msecs"
(def q12
  '{:find [l_shipmode
           (sum (if (xtdb.fixtures.tpch/high-lines o_orderpriority) 1 0))
           (sum (if (xtdb.fixtures.tpch/high-lines o_orderpriority) 0 1))]
    :where [[l :l_orderkey o]
            [l :l_receiptdate l_receiptdate]
            [l :l_commitdate l_commitdate]
            [l :l_shipdate l_shipdate]
            [(>= l_receiptdate #inst "1994-01-01")]
            [(< l_receiptdate #inst "1995-01-01")]
            [(< l_commitdate l_receiptdate)]
            [(< l_shipdate l_commitdate)]
            [l :l_shipmode l_shipmode]
            [l :l_shipmode #{"MAIL" "SHIP"}]
            [o :o_orderpriority o_orderpriority]]
    :order-by [[l_shipmode :asc]]})

;; "Elapsed time: 5378.248722 msecs"
;; "Elapsed time: 3508.629098 msecs"
(def q13
  '{:find [c_count (count c_count)]
    :where [(or [(q {:find [c (count o)]
                     :where [[o :o_custkey c]
                             [o :o_comment o_comment]
                             (not [(re-find #".*special.*requests.*" o_comment)])]}) [[c c_count]]]
                (and [c :c_custkey]
                     (not [_ :o_custkey c])
                     [(identity 0) c_count]))]
    :order-by [[(count c_count) :desc] [c_count :desc]]})

;; "Elapsed time: 254.603495 msecs"
;; "Elapsed time: 99.009169 msecs"
(def q14
  '{:find [(* 100 (/ promo total))]
    :where [[(q {:find [(sum (if (clojure.string/starts-with? p_type "PROMO")
                               (* l_extendedprice (- 1 l_discount))
                               0))
                        (sum (* l_extendedprice (- 1 l_discount)))]
                 :where [[l :l_partkey p]
                         [p :p_type p_type]
                         [l :l_shipdate l_shipdate]
                         [l :l_extendedprice l_extendedprice]
                         [l :l_discount l_discount]
                         [(>= l_shipdate #inst "1995-09-01")]
                         [(< l_shipdate #inst "1995-10-01")]]})
             [[promo total]]]]})

;; "Elapsed time: 274.478701 msecs"
;; "Elapsed time: 199.634724 msecs"
(def q15
  '{:find [s s_name s_address s_phone total_revenue]
    :where [[(q {:find [s (* l_extendedprice (- 1 l_discount))]
                 :where [[l :l_suppkey s]
                         [l :l_shipdate l_shipdate]
                         [l :l_extendedprice l_extendedprice]
                         [l :l_discount l_discount]
                         [(>= l_shipdate #inst "1996-01-01")]
                         [(< l_shipdate #inst "1996-04-01")]]})
             revenue]
            [(q {:find [(max total_revenue)]
                 :in [$ [[_ total_revenue]]]} revenue) [[total_revenue]]]
            [(identity revenue) [[s total_revenue]]]
            [s :s_name s_name]
            [s :s_address s_address]
            [s :s_phone s_phone]]})

;; "Elapsed time: 607.135035 msecs"
;; "Elapsed time: 426.025221 msecs"
(def q16
  '{:find [p_brand
           p_type
           p_size
           (count-distinct s)]
    :where [[p :p_brand p_brand]
            [(not= p_brand "Brand#45")]
            [p :p_type p_type]
            (not [(re-find #"^MEDIUM POLISHED.*" p_type)])
            [p :p_size p_size]
            [p :p_size #{49 14 23 45 19 3 36 9}]
            [ps :ps_partkey p]
            [ps :ps_suppkey s]
            (not-join [s]
                      [s :s_comment s_comment]
                      [(re-find #".*Customer.*Complaints.*" s_comment)])]
    :order-by [[(count-distinct s) :desc]
               [p_brand :asc]
               [p_type :asc]
               [p_size :asc]]})

;; "Elapsed time: 26.988817 msecs"
;; "Elapsed time: 9.406796 msecs"
(def q17
  '{:find [avg_yearly]
    :where [[(q {:find [(sum l_extendedprice)]
                 :where [[p :p_brand "Brand#23"]
                         [p :p_container "MED BOX"]
                         [l :l_partkey p]
                         [(q {:find [(avg l_quantity)]
                              :in [$ p]
                              :where [[l :l_partkey p]
                                      [l :l_quantity l_quantity]]} p) [[avg_quantity]]]
                         [(* 0.2 avg_quantity) ret_1]
                         [l :l_quantity l_quantity]
                         [(< l_quantity ret_1)]
                         [l :l_extendedprice l_extendedprice]]}) [[sum_extendedprice]]]
            [(/ sum_extendedprice 7.0) avg_yearly]]})

;; "Elapsed time: 5612.090612 msecs"
;; "Elapsed time: 3852.92785 msecs"
(def q18
  '{:find [c_name c o o_orderdate o_totalprice sum_quantity]
    :where [[(q {:find [o (sum l_quantity)]
                 :where [[l :l_orderkey o]
                         [l :l_quantity l_quantity]]})
             [[o sum_quantity]]]
            [(> sum_quantity 300.0)]
            [o :o_custkey c]
            [c :c_name c_name]
            [o :o_orderdate o_orderdate]
            [o :o_totalprice o_totalprice]]
    :order-by [[o_totalprice :desc] [o_orderdate :asc]]
    :limit 100})

;; "Elapsed time: 3669.359824 msecs"
;; "Elapsed time: 2275.890159 msecs"
(def q19
  '{:find [(sum (* l_extendedprice (- 1 l_discount)))]
    :where [[l :l_shipmode #{"AIR" "AIR REG"}]
            [l :l_shipinstruct "DELIVER IN PERSON"]
            [l :l_discount l_discount]
            [l :l_extendedprice l_extendedprice]
            [l :l_partkey p]
            [l :l_quantity l_quantity]
            [p :p_size p_size]
            (or (and [p :p_brand "Brand#12"]
                     [p :p_container #{"SM CASE" "SM BOX" "SM PACK" "SM PKG"}]
                     [(>= l_quantity 1.0)]
                     [(<= l_quantity 11.0)]
                     [(>= p_size 1)]
                     [(<= p_size 5)])
                (and [p :p_brand "Brand#23"]
                     [p :p_container #{"MED BAG" "MED BOX" "MED PKG" "MED PACK"}]
                     [(>= l_quantity 10.0)]
                     [(<= l_quantity 20.0)]
                     [(>= p_size 1)]
                     [(<= p_size 10)])
                (and [p :p_brand "Brand#34"]
                     [p :p_container #{"LG CASE" "LG BOX" "LG PACK" "LG PKG"}]
                     [(>= l_quantity 20.0)]
                     [(<= l_quantity 30.0)]
                     [(>= p_size 1)]
                     [(<= p_size 15)]))]})

;; "Elapsed time: 1814.036551 msecs"
;; "Elapsed time: 1200.295518 msecs"
(def q20
  '{:find [s_name
           s_address]
    :where [[ps :ps_suppkey s]
            [ps :ps_partkey p]
            [p :p_name p_name]
            [(re-find #"^forest.*" p_name)]
            [(q {:find [(sum l_quantity)]
                 :in [$ p s]
                 :where [[l :l_partkey p]
                         [l :l_suppkey s]
                         [l :l_shipdate l_shipdate]
                         [(>= l_shipdate #inst "1994-01-01")]
                         [(< l_shipdate #inst "1995-01-01")]
                         [l :l_quantity l_quantity]]} p s) [[sum_quantity]]]
            [(* sum_quantity 0.5) ret_1]
            [(long ret_1) ret_2]
            [ps :ps_availqty ps_availqty]
            [(> ps_availqty ret_2)]
            [s :s_name s_name]
            [s :s_address s_address]
            [s :s_nationkey n]
            [n :n_name "CANADA"]]
    :order-by [[s_name :asc]]})

;; "Elapsed time: 1562.348211 msecs"
;; "Elapsed time: 1150.334476 msecs"
(def q21
  '{:find [s_name
           (count l1)]
    :where [[l1 :l_suppkey s]
            [s :s_name s_name]
            [l1 :l_orderkey o]
            [o :o_orderstatus "F"]
            [l1 :l_receiptdate l_receiptdate]
            [l1 :l_commitdate l_commitdate]
            [(> l_receiptdate l_commitdate)]
            (or-join [o s]
                     (and [l2 :l_orderkey o]
                          (not [l2 :l_suppkey s])))
            (not-join [o s]
                      [l3 :l_orderkey o]
                      (not [l3 :l_suppkey s])
                      [l3 :l_receiptdate l_receiptdate]
                      [l3 :l_commitdate l_commitdate]
                      [(> l_receiptdate l_commitdate)])
            [s :s_nationkey n]
            [n :n_name "SAUDI ARABIA"]]
    :order-by  [[(count l1) :desc] [s_name :asc]]
    :limit 100})

;; "Elapsed time: 673.423196 msecs"
;; "Elapsed time: 570.884153 msecs"
(def q22
  '{:find [cntrycode
           (count c)
           (sum c_acctbal)]
    :where [[c :c_phone c_phone]
            [(subs c_phone 0 2) cntrycode]
            [(contains? #{"13" "31" "23" "29" "30" "18" "17"} cntrycode)]
            [(q {:find [(avg c_acctbal)]
                 :where [[c :c_acctbal c_acctbal]
                         [(> c_acctbal 0.0)]
                         [c :c_phone c_phone]
                         [(subs c_phone 0 2) cntrycode]
                         [(contains? #{"13" "31" "23" "29" "30" "18" "17"} cntrycode)]]}) [[avg_acctbal]]]
            [c :c_acctbal c_acctbal]
            [(> c_acctbal avg_acctbal)]
            (not-join [c]
                      [o :o_custkey c])]
    :order-by [[cntrycode :asc]]})

(def tpch-queries [q1 q2 q3 q4 q5 q6 q7 q8 q9 q10 q11 q12 q13 q14 q15 q16 q17 q18 q19 q20 q21 q22])

(defn parse-tpch-result [n]
  (let [result-csv (slurp (io/resource (format "io/airlift/tpch/queries/q%d.result" n)))
        [_head & lines] (str/split-lines result-csv)]
    (vec (for [l lines]
           (vec (for [x (str/split l #"\|")
                      :let [x (try
                                (cond
                                  (re-find #"[ ,]" x)
                                  x
                                  (re-find #"\d\d\d\d-\d\d-\d\d" x)
                                  (i/read-instant-date x)
                                  :else
                                  (edn/read-string x))
                                (catch Exception _
                                  x))]]
                  (cond-> x
                    (symbol? x) (str))))))))

(defn validate-tpch-query [actual expected]
  (->> (for [[expected-row actual-row] (map vector expected (if (empty? actual)
                                                              [["null"]]
                                                              actual))
             :let [msg (pr-str [expected-row actual-row])]]
         (and (t/is (= (count expected-row)
                       (count actual-row)))
              (every? true?
                      (mapv
                       (fn [x y]
                         (cond
                           (and (number? x) (string? y))
                           (t/is (str/ends-with? y (str x)) msg)

                           (and (number? x) (number? y))
                           (let [epsilon 0.01
                                 diff (Math/abs (- (double x) (double y)))]
                             (t/is (<= diff epsilon)))
                           :else
                           (t/is (= x y) msg)))
                       expected-row
                       actual-row))))
       (every? boolean)))

(comment
  (require '[xtdb.query :as q] 'dev)

  ;; SF 0.01
  (let [node (dev/xtdb-node)]
    (time (load-docs! node 0.01))
    (prn (xt/attribute-stats node)))

  ;; SQL:
  (slurp (io/resource "io/airlift/tpch/queries/q1.sql"))
  ;; Results:
  (slurp (io/resource "io/airlift/tpch/queries/q1.result"))

  (let [node (dev/xtdb-node)]
    (time
     (doseq [n (range 1 23)]
       (time
        (let [db (xt/db node)
              query (assoc (get tpch-queries (dec n)) :timeout 120000)
              actual (xt/q db query)]
          (prn n
               (:vars-in-join-order (q/query-plan-for db query (::in-args (meta query))))
               (validate-tpch-query actual (parse-tpch-result n))))))))

  )
