(ns xtdb.types-test
  (:require [clojure.test :as t]
            [xtdb.test-util :as tu]
            [xtdb.time :as time]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr]
            [xtdb.vector.writer :as vw])
  (:import (java.math BigDecimal)
           java.net.URI
           java.nio.ByteBuffer
           (java.time Instant LocalDate LocalTime OffsetDateTime ZonedDateTime)
           (org.apache.arrow.vector BigIntVector BitVector DateDayVector DecimalVector Float4Vector Float8Vector IntVector IntervalMonthDayNanoVector NullVector SmallIntVector TimeNanoVector TimeStampMicroTZVector TinyIntVector VarBinaryVector VarCharVector)
           (org.apache.arrow.vector.complex DenseUnionVector ListVector StructVector)
           (org.apache.arrow.vector.types.pojo ArrowType)
           (xtdb.types IntervalDayTime IntervalYearMonth)
           (xtdb.vector IVectorWriter)
           (xtdb.vector.extensions KeywordVector TransitVector UriVector UuidVector)))

(t/use-fixtures :each tu/with-allocator)

(defn- test-read [arrow-type-fn write-fn vs]
  ;; TODO no longer types, but there are other things in here that depend on `test-read`
  (with-open [duv (DenseUnionVector/empty "" tu/*allocator*)]
    (let [duv-writer (vw/->writer duv)]
      (doseq [v vs]
        (doto (.legWriter duv-writer ^ArrowType (arrow-type-fn v))
          (write-fn v)))

      (let [duv-rdr (vw/vec-wtr->rdr duv-writer)]
        {:vs (vec (for [idx (range (count vs))]
                    (.getObject duv-rdr idx #xt/key-fn :kebab-case-keyword)))
         :vec-types (vec (for [idx (range (count vs))]
                           (class (.getVectorByType duv (.getTypeId duv idx)))))}))))

(defn- test-round-trip [vs]
  (test-read vw/value->arrow-type #(.writeObject ^IVectorWriter %1 %2) vs))

(t/deftest round-trips-values
  (t/is (= {:vs [false nil 2 1 6 4 3.14 2.0 BigDecimal/ONE]
            :vec-types [BitVector NullVector BigIntVector TinyIntVector SmallIntVector IntVector Float8Vector Float4Vector DecimalVector]}
           (test-round-trip [false nil (long 2) (byte 1) (short 6) (int 4) (double 3.14) (float 2) BigDecimal/ONE]))
        "primitives")

  (t/is (= {:vs ["Hello"
                 (ByteBuffer/wrap (byte-array [1, 2, 3]))
                 (ByteBuffer/wrap (byte-array [1, 2, 3]))]
            :vec-types [VarCharVector VarBinaryVector VarBinaryVector]}
           (test-round-trip ["Hello"
                             (byte-array [1 2 3])
                             (ByteBuffer/wrap (byte-array [1 2 3]))]))
        "binary types")

  (t/is (= {:vs [(time/->zdt #inst "1999")
                 (time/->zdt #inst "2021-09-02T13:54:35.809Z")
                 (ZonedDateTime/ofInstant (time/->instant #inst "2021-09-02T13:54:35.809Z") #xt.time/zone "Europe/Stockholm")
                 (ZonedDateTime/ofInstant (time/->instant #inst "2021-09-02T13:54:35.809Z") #xt.time/zone "+02:00")
                 (ZonedDateTime/ofInstant (Instant/ofEpochSecond 3600 1000) #xt.time/zone "UTC")]
            :vec-types (repeat 5 TimeStampMicroTZVector)}
           (test-round-trip [#inst "1999"
                             (time/->instant #inst "2021-09-02T13:54:35.809Z")
                             (ZonedDateTime/ofInstant (time/->instant #inst "2021-09-02T13:54:35.809Z") #xt.time/zone "Europe/Stockholm")
                             (OffsetDateTime/ofInstant (time/->instant #inst "2021-09-02T13:54:35.809Z") #xt.time/zone "+02:00")
                             (Instant/ofEpochSecond 3600 1234)]))
        "timestamp types")

  (let [vs [[]
            [2 3.14 [false nil]]
            {}
            {:b 2, :c 1, :f false}
            {:b 2, :c 1, :f false}
            [1 {:b [2]}]
            [1 {:b [2]}]
            {:b 3.14, :d {:e ["hello" -1]}}]]
    (t/is (= {:vs vs
              :vec-types [ListVector ListVector StructVector StructVector StructVector ListVector ListVector StructVector]}
             (test-round-trip vs))
          "nested types"))

  (let [vs [:foo :foo/bar #uuid "97a392d5-5e3f-406f-9651-a828ee79b156" (URI/create "https://xtdb.com") #xt/clj-form (fn [a b] (+ a b))]]
    (t/is (= {:vs vs
              :vec-types [KeywordVector KeywordVector UuidVector UriVector TransitVector]}
             (test-round-trip vs))
          "extension types")))

(t/deftest decimal-vector-test
  (let [vs [BigDecimal/ONE 123.45M 12.3M]]
    (->> "BigDecimal can be round tripped"
         (t/is (= {:vs vs
                   :vec-types [DecimalVector DecimalVector DecimalVector]}
                  (test-round-trip vs))))))

(t/deftest date-vector-test
  (let [vs [(LocalDate/of 2007 12 11)]]
    (->> "LocalDate can be round tripped through DAY date vectors"
         (t/is (= {:vs vs
                   :vec-types [DateDayVector]}
                  (test-round-trip vs))))

    (->> "LocalDate can be read from MILLISECOND date vectors"
         (t/is (= vs (:vs (test-read (constantly #xt.arrow/type [:date :milli])
                                     (fn [^IVectorWriter w ^LocalDate v]
                                       (.writeLong w (long (.toEpochDay v))))
                                     vs)))))))

(t/deftest time-vector-test
  (let [secs [(LocalTime/of 13 1 14 0)]
        micros [(LocalTime/of 13 1 14 1e3)]
        millis [(LocalTime/of 13 1 14 1e6)]
        nanos [(LocalTime/of 13 1 14 1e8)]
        all (concat secs millis micros nanos)]
    (->> "LocalTime can be round tripped through NANO time vectors"
         (t/is (= {:vs all
                   :vec-types (map (constantly TimeNanoVector) all)}
                  (test-round-trip all))))

    (->> "LocalTime can be read from SECOND time vectors"
         (t/is (= secs (:vs (test-read (constantly #xt.arrow/type [:time-local :second])
                                       (fn [^IVectorWriter w, ^LocalTime v]
                                         (.writeLong w (.toSecondOfDay v)))
                                       secs)))))

    (let [millis+ (concat millis secs)]
      (->> "LocalTime can be read from MILLI time vectors"
           (t/is (= millis+ (:vs (test-read (constantly #xt.arrow/type [:time-local :milli])
                                            (fn [^IVectorWriter w, ^LocalTime v]
                                              (.writeLong w (int (quot (.toNanoOfDay v) 1e6))))
                                            millis+))))))

    (let [micros+ (concat micros millis secs)]
      (->> "LocalTime can be read from MICRO time vectors"
           (t/is (= micros+ (:vs (test-read (constantly #xt.arrow/type [:time-local :micro])
                                            (fn [^IVectorWriter w, ^LocalTime v]
                                              (.writeLong w (long (quot (.toNanoOfDay v) 1e3))))
                                            micros+))))))))

(t/deftest interval-vector-test
  ;; for years/months we lose the years as a separate component, it has to be folded into months.
  (let [iym #xt/interval-ym "P35M"]
    (t/is (= [iym]
             (:vs (test-read (constantly #xt.arrow/type [:interval :year-month])
                             (fn [^IVectorWriter w, ^IntervalYearMonth v]
                               (.writeObject w v))
                             [iym])))))

  (let [idt #xt/interval-dt ["P1434D" "PT0.023S"]]
    (t/is (= [idt]
             (:vs (test-read (constantly #xt.arrow/type [:interval :day-time])
                             (fn [^IVectorWriter w, ^IntervalDayTime v]
                               (.writeObject w v))
                             [idt])))))

  (let [imdn #xt/interval-mdn ["P33M244D" "PT0.003444443S"]]
    (t/is (= {:vs [imdn]
              :vec-types [IntervalMonthDayNanoVector]}
             (test-round-trip [imdn])))))

(t/deftest test-merge-col-types
  (t/is (= :utf8 (types/merge-col-types :utf8 :utf8)))

  (t/is (= [:union #{:utf8 :i64}]
           (types/merge-col-types :utf8 :i64)))

  (t/is (= [:union #{:utf8 :i64 :f64}]
           (types/merge-col-types [:union #{:utf8 :i64}] :f64)))

  (t/testing "merges list types"
    (t/is (= [:list :utf8]
             (types/merge-col-types [:list :utf8] [:list :utf8])))

    (t/is (= [:list [:union #{:utf8 :i64}]]
             (types/merge-col-types [:list :utf8] [:list :i64])))

    (t/is (= [:list [:union #{:null :i64}]]
             (types/merge-col-types [:list :null] [:list :i64]))))

  (t/testing "merges struct types"
    (t/is (= '[:struct {a :utf8, b :utf8}]
             (types/merge-col-types '[:struct {a :utf8, b :utf8}]
                                    '[:struct {a :utf8, b :utf8}])))

    (t/is (= '[:struct {a :utf8
                        b [:union #{:utf8 :i64}]}]

             (types/merge-col-types '[:struct {a :utf8, b :utf8}]
                                    '[:struct {a :utf8, b :i64}])))

    (t/is (= '[:union #{[:struct {a :utf8, b [:union #{:utf8 :i64}]}] :null}]
             (types/merge-col-types '[:union #{:null [:struct {a :utf8, b :utf8}]}]
                                    '[:struct {a :utf8, b :i64}])))

    (let [struct0 '[:struct {a :utf8, b :utf8}]
          struct1 '[:struct {b :utf8, c :i64}]]
      (t/is (= '[:struct {a [:union #{:utf8 :null}]
                          b :utf8
                          c [:union #{:i64 :null}]}]
               (types/merge-col-types struct0 struct1))))

    (t/is (= '[:union #{:f64 [:struct {a [:union #{:i64 :utf8}]}]}]
             (types/merge-col-types '[:union #{:f64, [:struct {a :i64}]}]
                                    '[:struct {a :utf8}]))))

  (t/testing "null behaviour"
    (t/is (= :null
             (types/merge-col-types :null)))

    (t/is (= :null
             (types/merge-col-types :null :null)))

    (t/is (= [:union #{:null :i64}]
             (types/merge-col-types :null :i64))))

  (t/testing "sets"
    (t/is (= [:set :i64]
             (types/merge-col-types [:set :i64])))

    (t/is (= [:set :i64]
             (types/merge-col-types [:set :i64] [:set :i64])))

    (t/is (= [:set [:union #{:i64 :utf8}]]
             (types/merge-col-types [:set :i64] [:set :utf8]))))


  (t/testing "no struct squashing"
    (t/is (= '[:struct {foo [:struct {bibble :bool}]}]
             (types/merge-col-types '[:struct {foo [:struct {bibble :bool}]}])))))

(t/deftest test-merge-fields
  (t/is (= (types/col-type->field :utf8)
           (types/merge-fields (types/col-type->field :utf8) (types/col-type->field :utf8))))

  (t/is (= (types/col-type->field [:union #{:utf8 :i64}])
           (types/merge-fields (types/col-type->field :utf8) (types/col-type->field :i64))))

  (t/is (=
         ;; ordering seems to be important
         ;; (types/col-type->field [:union #{:utf8 :i64 :f64}])
         (types/->field-default-name #xt.arrow/type :union false
                                     [(types/col-type->field :utf8)
                                      (types/col-type->field :i64)
                                      (types/col-type->field :f64)])
         (types/merge-fields (types/col-type->field [:union #{:utf8 :i64}]) (types/col-type->field :f64))))

  (t/testing "merges list types"
    (t/is (= (types/col-type->field [:list :utf8])
             (types/merge-fields (types/col-type->field [:list :utf8])
                                 (types/col-type->field [:list :utf8]))))

    (t/is (= (types/col-type->field [:list [:union #{:utf8 :i64}]])
             (types/merge-fields (types/col-type->field [:list :utf8])
                                 (types/col-type->field [:list :i64]))))

    (t/is (= (types/->field-default-name #xt.arrow/type :list false
                                         [(types/col-type->field "i64" [:union #{:null :i64}])])
             #_(types/col-type->field [:list [:union #{:null :i64}]])
             (types/merge-fields (types/col-type->field [:list :null])
                                 (types/col-type->field [:list :i64])))))

  (t/testing "merges struct types"
    (t/is (= (types/col-type->field '[:struct {a :utf8, b :utf8}])
             (types/merge-fields (types/col-type->field '[:struct {a :utf8, b :utf8}])
                                 (types/col-type->field '[:struct {a :utf8, b :utf8}]))))

    (t/is (= (types/col-type->field '[:struct {a :utf8
                                               b [:union #{:utf8 :i64}]}])
             (types/merge-fields (types/col-type->field '[:struct {a :utf8, b :utf8}])
                                 (types/col-type->field '[:struct {a :utf8, b :i64}]))))

    (t/is (= (types/col-type->field "struct" '[:union #{[:struct {a :utf8
                                                                  b [:union #{:utf8 :i64}]}]
                                                        :null}])
             (types/merge-fields (types/col-type->field '[:union #{[:struct {a :utf8, b :utf8}] :null}])
                                 (types/col-type->field '[:struct {a :utf8, b :i64}]))))

    (let [struct0 (types/col-type->field '[:struct {a :utf8, b :utf8}])
          struct1 (types/col-type->field '[:struct {b :utf8, c :i64}])]
      (t/is (= (types/col-type->field '[:struct {a [:union #{:utf8 :null}]
                                                 b :utf8
                                                 c [:union #{:i64 :null}]}])
               (types/merge-fields struct0 struct1))))

    (t/is (= #_(types/col-type->field '[:union #{:f64 [:struct {a [:union #{:utf8 :i64}]}]}])
             (types/->field-default-name #xt.arrow/type :union false
                                         [(types/col-type->field :f64)
                                          (types/->field-default-name #xt.arrow/type :struct false
                                                                      [(types/->field "a" #xt.arrow/type :union false
                                                                                      (types/col-type->field :i64)
                                                                                      (types/col-type->field :utf8))])])
             (types/merge-fields (types/col-type->field '[:union #{:f64, [:struct {a :i64}]}])
                                 (types/col-type->field '[:struct {a :utf8}]))))

    (let [struct0 (types/col-type->field '[:struct {a :i64
                                                    b [:struct {c :utf8, d :utf8}]}])
          struct1 (types/col-type->field '[:struct {a :bool
                                                    b :utf8}])]
      (t/is (= #_(types/col-type->field [:struct '{a [:union #{:i64 :bool}]
                                                   b [:union #{[:struct {c :utf8, d :utf8}]
                                                               :utf8}]}])
               (types/->field-default-name #xt.arrow/type :struct false
                                           [(types/->field "a" #xt.arrow/type :union false
                                                           (types/col-type->field :i64)
                                                           (types/col-type->field :bool))
                                            (types/->field "b" #xt.arrow/type :union false
                                                           (types/->field-default-name #xt.arrow/type :struct false
                                                                                       [(types/col-type->field "c" :utf8)
                                                                                        (types/col-type->field "d" :utf8)])
                                                           (types/col-type->field :utf8))])
               (types/merge-fields struct0 struct1)))))

  (t/testing "null behaviour"
    (t/is (= (types/col-type->field :null)
             (types/merge-fields (types/col-type->field :null))))

    (t/is (= (types/col-type->field :null)
             (types/merge-fields (types/col-type->field :null) (types/col-type->field :null))))

    (t/is (= (types/col-type->field "i64" [:union #{:null :i64}])
             (types/merge-fields (types/col-type->field :null) (types/col-type->field :i64))))

    (t/is (= (types/col-type->field "union" [:union #{:null :i64 :f64}])
             (types/merge-fields (types/col-type->field :f64) (types/col-type->field :null) (types/col-type->field :i64)))))

  (t/testing "sets"
    (t/is (= (types/col-type->field [:set :i64])
             (types/merge-fields (types/col-type->field [:set :i64]))))

    (t/is (= (types/col-type->field [:set :i64])
             (types/merge-fields (types/col-type->field [:set :i64]) (types/col-type->field [:set :i64]))))

    (t/is (= (types/col-type->field [:set [:union #{:i64 :utf8}]])
             (types/merge-fields (types/col-type->field [:set :utf8]) (types/col-type->field [:set :i64])))))

  (t/testing "no struct squashing"
    (t/is (= (types/col-type->field '[:struct {foo [:struct {bibble :bool}]}])
             (types/merge-fields (types/col-type->field '[:struct {foo [:struct {bibble :bool}]}]))))

    (t/is (= (types/->field-default-name #xt.arrow/type :struct false
                                         [(types/->field "foo" #xt.arrow/type :union false
                                                         (types/->field-default-name #xt.arrow/type :struct false
                                                                                     [(types/col-type->field "bibble" :bool)])
                                                         (types/col-type->field :utf8))
                                          (types/col-type->field "bar" [:union #{:null :i64}])])

             (types/merge-fields (types/col-type->field '[:struct {foo [:struct {bibble :bool}]}])
                                 (types/col-type->field '[:struct {foo :utf8 bar :i64}]))))))

(t/deftest test-pg-datetime-binary-roundtrip
  (doseq [{:keys [type val]} [{:val #xt.time/date "2018-07-25" :type :date}
                              {:val #xt.time/date-time "1441-07-25T18:00:11.888842" :type :timestamp}
                              {:val #xt.time/offset-date-time "1441-07-25T18:00:11.211142Z" :type :timestamptz}]]
    (let [{:keys [write-binary read-binary]} (get types/pg-types type)]

      (with-open [rdr (vr/vec->reader (vw/open-vec tu/*allocator* "val" [val]))
                  l-rdr (.legReader rdr (.getLeg rdr 0))]

        (t/is (= val (read-binary {} (write-binary {} l-rdr 0))))))))
