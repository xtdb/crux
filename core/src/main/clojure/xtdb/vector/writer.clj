(ns xtdb.vector.writer
  (:require [xtdb.error :as err]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector :as vec]
            [xtdb.vector.reader :as vr])
  (:import (clojure.lang Keyword)
           (java.lang AutoCloseable)
           (java.math BigDecimal)
           java.net.URI
           (java.nio ByteBuffer CharBuffer)
           java.nio.charset.StandardCharsets
           (java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetDateTime ZoneOffset ZonedDateTime)
           (java.util ArrayList Date HashMap LinkedHashMap List Map Set UUID)
           (java.util.function Function)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.vector BigIntVector BitVector DecimalVector DateDayVector DateMilliVector DurationVector ExtensionTypeVector FixedSizeBinaryVector Float4Vector Float8Vector IntVector IntervalDayVector IntervalMonthDayNanoVector IntervalYearVector NullVector PeriodDuration SmallIntVector TimeMicroVector TimeMilliVector TimeNanoVector TimeSecVector TimeStampVector TinyIntVector ValueVector VarBinaryVector VarCharVector VectorSchemaRoot)
           (org.apache.arrow.vector.complex DenseUnionVector ListVector StructVector)
           (org.apache.arrow.vector.types.pojo ArrowType$List ArrowType$Struct ArrowType$Union Field FieldType)
           xtdb.types.ClojureForm
           (xtdb.types IntervalDayTime IntervalMonthDayNano IntervalYearMonth)
           (xtdb.vector IRelationWriter RelationReader IRowCopier IVectorReader IVectorWriter IVectorPosition)
           (xtdb.vector.extensions SetType)))

(set! *unchecked-math* :warn-on-boxed)

(defprotocol WriterFactory
  (^xtdb.vector.IVectorWriter ->writer* [arrow-vec notify!]
   "->writer* will call `notify!` whenever its col-type changes"))

(defn ->writer ^xtdb.vector.IVectorWriter [arrow-vec]
  (->writer* arrow-vec (fn [_])))

(defprotocol ArrowWriteable
  (value->col-type [v])
  (write-value! [v ^xtdb.vector.IVectorWriter writer]))

(defn- null->vec-copier [^IVectorWriter dest-wtr]
  (let [wp (.writerPosition dest-wtr)]
    (reify IRowCopier
      (copyRow [_ _src-idx]
        (let [pos (.getPosition wp)]
          (.writeNull dest-wtr nil)
          pos)))))

(defn- duv->vec-copier [^IVectorWriter dest-wtr, ^DenseUnionVector src-vec]
  (let [copiers (object-array (for [child-vec src-vec]
                                (.rowCopier dest-wtr child-vec)))]
    (reify IRowCopier
      (copyRow [_ src-idx]
        (.copyRow ^IRowCopier (aget copiers (.getTypeId src-vec src-idx))
                  (.getOffset src-vec src-idx))))))

(defn- scalar-copier [^IVectorWriter dest-wtr, ^ValueVector src-vec]
  (let [wp (.writerPosition dest-wtr)
        dest-vec (.getVector dest-wtr)]
    (cond
      (instance? NullVector src-vec) (null->vec-copier dest-wtr)
      (instance? DenseUnionVector src-vec) (duv->vec-copier dest-wtr src-vec)

      :else
      (reify IRowCopier
        (copyRow [_ src-idx]
          (let [pos (.getPositionAndIncrement wp)]
            (.copyFromSafe dest-vec src-idx pos src-vec)
            pos))))))

(extend-protocol WriterFactory
  NullVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] :null)

        (clear [_] (.clear arrow-vec) (.setPosition wp 0))

        (rowCopier [this-wtr _src-vec]
          ;; NullVector throws UOE on copyValueSafe 🙄
          (reify IRowCopier
            (copyRow [_ _src-idx]
              (let [pos (.getPosition wp)]
                (.writeNull this-wtr nil)
                pos))))

        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writerForType [this _col-type] this))))

  BitVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] :bool)
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeBoolean [_ v] (.setSafe arrow-vec (.getPositionAndIncrement wp) (if v 1 0)))
        (writerForType [this _col-type] this)))))

(defmacro def-writer-factory [clazz write-method]
  `(extend-protocol WriterFactory
     ~clazz
     (~'->writer* [arrow-vec# _notify!#]
      (let [wp# (IVectorPosition/build (.getValueCount arrow-vec#))
            col-type# (types/field->col-type (.getField arrow-vec#))]
        (reify IVectorWriter
          (~'getVector [_] arrow-vec#)
          (~'getColType [_] col-type#)
          (~'clear [_] (.clear arrow-vec#) (.setPosition wp# 0))
          (~'rowCopier [this# src-vec#] (scalar-copier this# src-vec#))
          (~'writerPosition [_#] wp#)

          (~'writeNull [_# _#] (.setNull arrow-vec# (.getPositionAndIncrement wp#)))

          (~write-method [_# v#]
           (.setSafe arrow-vec# (.getPositionAndIncrement wp#) v#))

          (~'writerForType [this# _col-type#] this#))))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(do
  (def-writer-factory TinyIntVector writeByte)
  (def-writer-factory SmallIntVector writeShort)
  (def-writer-factory IntVector writeInt)
  (def-writer-factory BigIntVector writeLong)
  (def-writer-factory Float4Vector writeFloat)
  (def-writer-factory Float8Vector writeDouble)

  (def-writer-factory DateDayVector writeLong)
  (def-writer-factory TimeStampVector writeLong)
  (def-writer-factory TimeSecVector writeLong)
  (def-writer-factory TimeMilliVector writeLong)
  (def-writer-factory TimeMicroVector writeLong)
  (def-writer-factory TimeNanoVector writeLong))

(extend-protocol WriterFactory
  DateMilliVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] [:date :milli])
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeLong [_ days] (.setSafe arrow-vec (.getPositionAndIncrement wp) (* days 86400000)))))))

(extend-protocol WriterFactory
  DecimalVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] :decimal)
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeObject [_  decimal]
          (let [new-decimal (.setScale ^BigDecimal decimal (.getScale arrow-vec))]
            (.setSafe arrow-vec (.getPositionAndIncrement wp) new-decimal)))
        (writerForType [this _col-type] this)))))

(extend-protocol ArrowWriteable
  nil
  (value->col-type [_] :null)
  (write-value! [_v ^IVectorWriter w] (.writeNull w nil))

  Boolean
  (value->col-type [_] :bool)
  (write-value! [v ^IVectorWriter w] (.writeBoolean w v))

  Byte
  (value->col-type [_] :i8)
  (write-value! [v ^IVectorWriter w] (.writeByte w v))

  Short
  (value->col-type [_] :i16)
  (write-value! [v ^IVectorWriter w] (.writeShort w v))

  Integer
  (value->col-type [_] :i32)
  (write-value! [v ^IVectorWriter w] (.writeInt w v))

  Long
  (value->col-type [_] :i64)
  (write-value! [v ^IVectorWriter w] (.writeLong w v))

  Float
  (value->col-type [_] :f32)
  (write-value! [v ^IVectorWriter w] (.writeFloat w v))

  Double
  (value->col-type [_] :f64)
  (write-value! [v ^IVectorWriter w] (.writeDouble w v))

  BigDecimal
  (value->col-type [_] :decimal)
  (write-value! [v ^IVectorWriter w] (.writeObject w v)))

(extend-protocol WriterFactory
  DurationVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))
          col-type (types/field->col-type (.getField arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] col-type)
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeLong [_ v] (.setSafe arrow-vec (.getPositionAndIncrement wp) v))
        (writerForType [this _col-type] this))))

  IntervalYearVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] [:interval :year-month])
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeInt [_ total-months] (.setSafe arrow-vec (.getPositionAndIncrement wp) total-months))
        (writeObject [this v]
          (let [^PeriodDuration period-duration v]
            (.writeInt this (.toTotalMonths (.getPeriod period-duration)))))
        (writerForType [this _col-type] this))))

  IntervalDayVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] [:interval :day-time])
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeObject [_ v]
          (let [^PeriodDuration period-duration v
                duration (.getDuration period-duration)
                dsecs (.getSeconds duration)
                dmillis (.toMillisPart duration)
                period (.getPeriod period-duration)]
            (when (or (not (zero? (.getYears period))) (not (zero? (.getMonths period))))
              (throw (err/illegal-arg "Period of PeriodDuration can not contain years or months!")))
            (.setSafe arrow-vec (.getPositionAndIncrement wp)
                      (.getDays (.getPeriod period-duration))
                      (Math/addExact (Math/multiplyExact (long dsecs) (long 1000)) (long dmillis)))))
        (writerForType [this _col-type] this))))

  IntervalMonthDayNanoVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] [:interval :month-day-nano])
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))
        (writeObject [_ v]
          (let [^PeriodDuration period-duration v
                period (.getPeriod period-duration)
                duration (.getDuration period-duration)
                dsecs (.getSeconds duration)
                dnanos (.toNanosPart duration)]
            (.setSafe arrow-vec (.getPositionAndIncrement wp)
                      (.toTotalMonths period)
                      (.getDays period)
                      (Math/addExact (Math/multiplyExact dsecs (long 1000000000)) (long dnanos)))))
        (writerForType [this _col-type] this)))))

(extend-protocol ArrowWriteable
  Date
  (value->col-type [_] [:timestamp-tz :micro "UTC"])
  (write-value! [v ^IVectorWriter w] (.writeLong w (Math/multiplyExact (.getTime v) 1000)))

  Instant
  (value->col-type [_] [:timestamp-tz :micro "UTC"])
  (write-value! [v ^IVectorWriter w] (.writeLong w (util/instant->micros v)))

  ZonedDateTime
  (value->col-type [v] [:timestamp-tz :micro (.getId (.getZone v))])
  (write-value! [v ^IVectorWriter w] (write-value! (.toInstant v) w))

  OffsetDateTime
  (value->col-type [v] [:timestamp-tz :micro (.getId (.getOffset v))])
  (write-value! [v ^IVectorWriter w] (write-value! (.toInstant v) w))

  LocalDateTime
  (value->col-type [_] [:timestamp-local :micro])
  (write-value! [v ^IVectorWriter w] (write-value! (.toInstant v ZoneOffset/UTC) w))

  Duration
  (value->col-type [_] [:duration :micro])
  (write-value! [v ^IVectorWriter w] (.writeLong w (quot (.toNanos v) 1000)))

  LocalDate
  (value->col-type [_] [:date :day])
  (write-value! [v ^IVectorWriter w] (.writeLong w (.toEpochDay v)))

  LocalTime
  (value->col-type [_] [:time-local :nano])
  (write-value! [v ^IVectorWriter w] (.writeLong w (.toNanoOfDay v)))

  IntervalYearMonth
  (value->col-type [_] [:interval :year-month])
  (write-value! [v ^IVectorWriter w] (.writeInt w (.toTotalMonths (.period v))))

  IntervalDayTime
  (value->col-type [_] [:interval :day-time])
  (write-value! [v ^IVectorWriter w] (write-value! (PeriodDuration. (.period v) (.duration v)) w))

  IntervalMonthDayNano
  (value->col-type [_] [:interval :month-day-nano])
  (write-value! [v ^IVectorWriter w] (write-value! (PeriodDuration. (.period v) (.duration v)) w))

  ;; allow the use of PeriodDuration for more precision
  PeriodDuration
  (value->col-type [_] [:interval :month-day-nano])
  (write-value! [v ^IVectorWriter w] (.writeObject w v)))

(extend-protocol WriterFactory
  VarCharVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] :utf8)
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))

        (writeBytes [_ v]
          (let [pos (.position v)]
            (.setSafe arrow-vec (.getPositionAndIncrement wp)
                      v (.position v) (.remaining v))
            (.position v pos)))

        (writeObject [this v]
          (.writeBytes this (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap ^CharSequence v))))

        (writerForType [this _] this))))

  VarBinaryVector
  (->writer* [arrow-vec _notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] :varbinary)
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))

        (writeBytes [_ v]
          (let [pos (.position v)]
            (.setSafe arrow-vec (.getPositionAndIncrement wp)
                      v (.position v) (.remaining v))
            (.position v pos)))

        (writeObject [this v] (.writeBytes this (ByteBuffer/wrap ^bytes v)))

        (writerForType [this _] this))))

  FixedSizeBinaryVector
  (->writer* [arrow-vec _notify!]
    (let [byte-width (.getByteWidth arrow-vec)
          wp (IVectorPosition/build (.getValueCount arrow-vec))]
      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] [:fixed-size-binary byte-width])
        (clear [_] (.clear arrow-vec) (.setPosition wp 0))
        (rowCopier [this src-vec] (scalar-copier this src-vec))
        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))

        (writeBytes [_ buf]
          (let [pos (.position buf)
                idx (.getPositionAndIncrement wp)]
            (.setIndexDefined arrow-vec idx)
            (.setBytes (.getDataBuffer arrow-vec) (* byte-width idx) buf)
            (.position buf pos)))

        (writeObject [this bytes] (.writeBytes this (ByteBuffer/wrap bytes)))

        (writerForType [this _] this)))))

(extend-protocol ArrowWriteable
  (Class/forName "[B")
  (value->col-type [_] :varbinary)
  (write-value! [v ^IVectorWriter w] (.writeObject w v))

  ByteBuffer
  (value->col-type [_] :varbinary)
  (write-value! [v ^IVectorWriter w] (.writeBytes w v))

  CharSequence
  (value->col-type [_] :utf8)
  (write-value! [v ^IVectorWriter w] (.writeObject w v)))

(defn populate-with-absents [^IVectorWriter w, ^long pos]
  (let [absents (- pos (.getPosition (.writerPosition w)))]
    (when (pos? absents)
      (let [absent-writer (.writerForType w :absent)]
        (dotimes [_ absents]
          (.writeNull absent-writer nil))))))

(extend-protocol WriterFactory
  ListVector
  (->writer* [arrow-vec notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))
          data-vec (.getDataVector arrow-vec)
          !col-type (atom nil)
          el-writer (->writer* data-vec (fn notify-list-writer! [el-type]
                                          (notify! (reset! !col-type [:list el-type]))))
          el-wp (.writerPosition el-writer)]

      (reset! !col-type [:list (.getColType el-writer)])

      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] @!col-type)
        (clear [_] (.clear arrow-vec) (.setPosition wp 0) (.clear el-writer))

        (rowCopier [this-wtr src-vec]
          (cond
            (instance? NullVector src-vec) (null->vec-copier this-wtr)
            (instance? DenseUnionVector src-vec) (duv->vec-copier this-wtr src-vec)
            :else (let [^ListVector src-vec src-vec
                        data-vec (.getDataVector src-vec)
                        inner-copier (.rowCopier el-writer data-vec)]
                    (reify IRowCopier
                      (copyRow [_ src-idx]
                        (let [pos (.getPosition wp)]
                          (if (.isNull src-vec src-idx)
                            (.writeNull this-wtr nil)
                            (do
                              (.startList this-wtr)
                              (let [start-idx (.getElementStartIndex src-vec src-idx)
                                    end-idx (.getElementEndIndex src-vec src-idx)]
                                (dotimes [n (- end-idx start-idx)]
                                  (.copyRow inner-copier (+ start-idx n))))
                              (.endList this-wtr)))
                          pos))))))

        (writerPosition [_] wp)
        (writeNull [_ _] (.setNull arrow-vec (.getPositionAndIncrement wp)))

        (listElementWriter [_] el-writer)
        (startList [_]
          (.startNewValue arrow-vec (.getPosition wp)))

        (endList [_]
          (let [pos (.getPositionAndIncrement wp)
                end-pos (.getPosition el-wp)]
            (.endValue arrow-vec pos (- end-pos (.getElementStartIndex arrow-vec pos)))))

        (writerForType [this _col-type] this))))

  StructVector
  (->writer* [arrow-vec notify!]
    (let [wp (IVectorPosition/build (.getValueCount arrow-vec))
          writers (HashMap.)
          !col-types (atom {})]

      (letfn [(notify-struct! [col-name col-type]
                (notify! [:struct (swap! !col-types assoc (symbol col-name) col-type)]))
              (->key-writer [^ValueVector child-vec]
                (let [col-name (.getName child-vec)
                      w (->writer* child-vec (partial notify-struct! col-name))]
                  (notify-struct! col-name (.getColType w))
                  w))]

        (reify IVectorWriter
          (getVector [_] arrow-vec)

          (getColType [_] [:struct @!col-types])

          (clear [_] (.clear arrow-vec) (.setPosition wp 0) (run! #(.clear ^IVectorWriter %) (.values writers)))

          (rowCopier [this-wtr src-vec]
            (cond
              (instance? NullVector src-vec) (null->vec-copier this-wtr)
              (instance? DenseUnionVector src-vec) (duv->vec-copier this-wtr src-vec)
              :else (let [^StructVector src-vec src-vec
                          inner-copiers (mapv (fn [^ValueVector inner]
                                                (-> (.structKeyWriter this-wtr (.getName inner))
                                                    (.rowCopier inner)))
                                              src-vec)]
                      (reify IRowCopier
                        (copyRow [_ src-idx]
                          (let [pos (.getPosition wp)]
                            (if (.isNull src-vec src-idx)
                              (.writeNull this-wtr nil)
                              (do
                                (.startStruct this-wtr)
                                (doseq [^IRowCopier inner inner-copiers]
                                  (.copyRow inner src-idx))
                                (.endStruct this-wtr)))
                            pos))))))

          (writerPosition [_] wp)

          (writeNull [_ _]
            (.setNull arrow-vec (.getPositionAndIncrement wp))

            (doseq [^IVectorWriter w (.values writers)]
              (.writeNull w nil)))

          (structKeyWriter [_ col-name]
            (.computeIfAbsent writers col-name
                              (reify Function
                                (apply [_ col-name]
                                  (doto (or (some-> (.getChild arrow-vec col-name)
                                                    (->key-writer))

                                            (->key-writer (.addOrGet arrow-vec col-name
                                                                     (FieldType/notNullable types/dense-union-type)
                                                                     DenseUnionVector)))
                                    (populate-with-absents (.getPosition wp)))))))

          (structKeyWriter [_ col-name col-type]
            (.computeIfAbsent writers col-name
                              (reify Function
                                (apply [_ col-name]
                                  (doto (or (some-> (.getChild arrow-vec col-name)
                                                    (->key-writer))

                                            (let [field (types/col-type->field col-type)]
                                              (->key-writer (doto (.addOrGet arrow-vec col-name (.getFieldType field) ValueVector)
                                                              (.initializeChildrenFromFields (.getChildren field))))))

                                    (populate-with-absents (.getPosition wp)))))))

          (startStruct [_]
            (.setIndexDefined arrow-vec (.getPosition wp)))

          (endStruct [_]
            (let [pos (.getPositionAndIncrement wp)]
              (doseq [^IVectorWriter w (.values writers)]
                (populate-with-absents w (inc pos)))))

          (writerForType [this _col-type] this)

          Iterable
          (iterator [_] (.iterator (.entrySet writers))))))))

(extend-protocol ArrowWriteable
  List
  (value->col-type [v] [:list (apply types/merge-col-types (into #{} (map value->col-type) v))])
  (write-value! [v ^IVectorWriter writer]
    (let [el-writer (.listElementWriter writer)]
      (.startList writer)
      (doseq [el v]
        (write-value! el (.writerForType el-writer (value->col-type el))))
      (.endList writer)))

  Set
  (value->col-type [v] [:set (apply types/merge-col-types (into #{} (map value->col-type) v))])
  (write-value! [v ^IVectorWriter writer] (write-value! (vec v) writer))

  Map
  (value->col-type [v]
    (if (every? keyword? (keys v))
      [:struct (->> v
                    (into {} (map (juxt (comp symbol key)
                                        (comp value->col-type val)))))]
      [:map
       (apply types/merge-col-types (into #{} (map (comp value->col-type key)) v))
       (apply types/merge-col-types (into #{} (map (comp value->col-type val)) v))]))

  (write-value! [m ^IVectorWriter writer]
    (if (every? keyword? (keys m))
      (do
        (.startStruct writer)

        (doseq [[k v] m
                :let [v-writer (-> (.structKeyWriter writer (str (symbol k)))
                                   (.writerForType (value->col-type v)))]]
          (write-value! v v-writer))

        (.endStruct writer))

      (throw (UnsupportedOperationException.)))))

(defn- duv-child-writer [^IVectorWriter w, write-value!]
  (reify IVectorWriter
    (getVector [_] (.getVector w))
    (getColType [_] (.getColType w))
    (clear [_] (.clear w))

    (rowCopier [_ src-vec]
      (let [inner-copier (.rowCopier w src-vec)]
        (reify IRowCopier
          (copyRow [_ src-idx]
            (write-value!)
            (.copyRow inner-copier src-idx)))))

    (writerPosition [_] (.writerPosition w))

    (writeNull [_ v] (write-value!) (.writeNull w v))
    (writeBoolean [_ v] (write-value!) (.writeBoolean w v))
    (writeByte [_ v] (write-value!) (.writeByte w v))
    (writeShort [_ v] (write-value!) (.writeShort w v))
    (writeInt [_ v] (write-value!) (.writeInt w v))
    (writeLong [_ v] (write-value!) (.writeLong w v))
    (writeFloat [_ v] (write-value!) (.writeFloat w v))
    (writeDouble [_ v] (write-value!) (.writeDouble w v))
    (writeBytes [_ v] (write-value!) (.writeBytes w v))
    (writeObject [_ v] (write-value!) (.writeObject w v))

    (structKeyWriter [_ k] (.structKeyWriter w k))
    (startStruct [_] (.startStruct w))
    (endStruct [_] (write-value!) (.endStruct w))

    (listElementWriter [_] (.listElementWriter w))
    (startList [_] (.startList w))
    (endList [_] (write-value!) (.endList w))

    (registerNewType [_ field] (.registerNewType w field))
    (writerForType [_ col-type] (.writerForType w col-type))
    (writerForTypeId [_ type-id] (.writerForTypeId w type-id))))

(defn- duv->duv-copier ^xtdb.vector.IRowCopier [^IVectorWriter dest-col, ^DenseUnionVector src-vec]
  (let [src-field (.getField src-vec)
        src-type (.getType src-field)
        type-ids (.getTypeIds ^ArrowType$Union src-type)
        child-fields (.getChildren src-field)
        child-count (count child-fields)
        copier-mapping (object-array child-count)]

    (dotimes [n child-count]
      (let [src-type-id (or (when type-ids (aget type-ids n))
                            n)
            ^Field child-field (.get child-fields n)
            child-field-name (.getName child-field)
            col-type (types/field->col-type child-field)]
        (aset copier-mapping src-type-id
              ;; HACK to make things work for named duv legs
              (if-not (= child-field-name (types/col-type->field-name col-type))
                (.rowCopier (.writerForField dest-col child-field)
                            (.getVectorByType src-vec src-type-id))
                (.rowCopier (.writerForType dest-col col-type)
                            (.getVectorByType src-vec src-type-id))))))

    (reify IRowCopier
      (copyRow [_ src-idx]
        (try
          (let [type-id (.getTypeId src-vec src-idx)]
            (assert (not (neg? type-id)))
            (-> ^IRowCopier (aget copier-mapping type-id)
                (.copyRow (.getOffset src-vec src-idx))))
          (catch Throwable t
            (throw t)))))))

(defn- vec->duv-copier ^xtdb.vector.IRowCopier [^IVectorWriter dest-col, ^ValueVector src-vec]
  (let [field (.getField src-vec)
        inner-types (types/flatten-union-types (types/field->col-type field))
        without-null (disj inner-types :null)]
    (assert (<= (count without-null) 1))
    (let [non-null-copier (when-let [nn-col-type (first without-null)]
                            (-> (.writerForType dest-col nn-col-type)
                                (.rowCopier src-vec)))
          !null-copier (delay
                         (-> (.writerForType dest-col :null)
                             (.rowCopier src-vec)))]
      (reify IRowCopier
        (copyRow [_ src-idx]
          (if (.isNull src-vec src-idx)
            (.copyRow ^IRowCopier @!null-copier src-idx)
            (.copyRow non-null-copier src-idx)))))))

(extend-protocol WriterFactory
  DenseUnionVector
  (->writer* [duv notify!]
    (let [wp (IVectorPosition/build (.getValueCount duv))
          children (.getChildren (.getField duv))
          writers-by-type-id (ArrayList.)
          writers-by-type (HashMap.)
          writers-by-name (HashMap.)
          !col-type (atom nil)]

      (letfn [(->col-type []
                (apply types/merge-col-types (into #{} (map #(.getColType ^IVectorWriter %)) writers-by-type-id)))

              (->child-writer [^long type-id]
                (let [v (.getVectorByType duv type-id)
                      child-wtr (->writer* v (fn [_]
                                               (notify! (reset! !col-type (->col-type)))))
                      child-wp (.writerPosition child-wtr)

                      child-wtr (-> child-wtr
                                    (duv-child-writer (fn write-value! []
                                                        (let [pos (.getPositionAndIncrement wp)]
                                                          (.setTypeId duv pos type-id)
                                                          (.setOffset duv pos (.getPosition child-wp))))))]
                  (.add writers-by-type-id type-id child-wtr)
                  child-wtr))]

        (doseq [[type-id ^Field field] (map-indexed vector children)]
          (let [child-wtr (->child-writer type-id)
                col-type (-> (.getVectorByType duv type-id)
                             (.getField)
                             (types/field->col-type)
                             (types/col-type->leg))]
            (.put writers-by-type col-type child-wtr)
            (.put writers-by-name (.getName field) child-wtr)
            child-wtr))

        (reset! !col-type (->col-type))

        (reify IVectorWriter
          (getVector [_] duv)
          (getColType [_] @!col-type)

          (clear [_] (.clear duv) (.setPosition wp 0) (run! #(.clear ^IVectorWriter %) writers-by-type-id))
          (rowCopier [this-writer src-vec]
            (let [inner-copier (if (instance? DenseUnionVector src-vec)
                                 (duv->duv-copier this-writer src-vec)
                                 (vec->duv-copier this-writer src-vec))]
              (reify IRowCopier
                (copyRow [_ src-idx] (.copyRow inner-copier src-idx)))))

          (writerPosition [_] wp)

          (registerNewType [_ field]
            (let [type-id (.registerNewTypeId duv field)
                  new-vec (.createVector field (.getAllocator duv))]
              (.addVector duv type-id new-vec)
              (->child-writer type-id)
              (notify! (reset! !col-type (->col-type)))

              type-id))

          (writerForField [this field]
            ;; doesn't add into writers-by-type because we might have more than one field with similar types
            ;; so don't use both writerForField and writerForType on one writer.
            (.computeIfAbsent writers-by-name (.getName field)
                              (reify Function
                                (apply [_ _]
                                  (.writerForTypeId this (.registerNewType this field))))))

          (writerForType [this col-type]
            (.computeIfAbsent writers-by-type (types/col-type->leg col-type)
                              (reify Function
                                (apply [_ _]
                                  (let [field-name (types/col-type->field-name col-type)

                                        ^Field field (case (types/col-type-head col-type)
                                                       :list
                                                       (types/->field field-name ArrowType$List/INSTANCE false (types/->field "$data$" types/dense-union-type false))

                                                       :set
                                                       (types/->field field-name SetType/INSTANCE false (types/->field "$data$" types/dense-union-type false))

                                                       :struct
                                                       (types/->field field-name ArrowType$Struct/INSTANCE false)

                                                       (types/col-type->field field-name col-type))

                                        type-id (.registerNewType this field)

                                        wtr (.writerForTypeId this type-id)]

                                    (.put writers-by-name field-name wtr)
                                    wtr)))))

          (writerForTypeId [_ type-id]
            (.get writers-by-type-id type-id)))))))

(extend-protocol WriterFactory
  ExtensionTypeVector
  (->writer* [arrow-vec notify!]
    (let [!col-type (atom nil)
          inner (->writer* (.getUnderlyingVector arrow-vec)
                           (fn [_]
                             (reset! !col-type (types/field->col-type (.getField arrow-vec)))))]

      (reset! !col-type (types/field->col-type (.getField arrow-vec)))

      (reify IVectorWriter
        (getVector [_] arrow-vec)
        (getColType [_] @!col-type)
        (clear [_] (.clear inner))
        (rowCopier [this-wtr src-vec]
          (cond
            (instance? NullVector src-vec) (null->vec-copier this-wtr)
            (instance? DenseUnionVector src-vec) (duv->vec-copier this-wtr src-vec)
            :else (do
                    (assert (instance? ExtensionTypeVector src-vec)
                            (pr-str (class src-vec)))
                    (.rowCopier inner (.getUnderlyingVector ^ExtensionTypeVector src-vec)))))

        (writerPosition [_] (.writerPosition inner))

        (writeNull [_ v] (.writeNull inner v))
        (writeBoolean [_ v] (.writeBoolean inner v))
        (writeByte [_ v] (.writeByte inner v))
        (writeShort [_ v] (.writeShort inner v))
        (writeInt [_ v] (.writeInt inner v))
        (writeLong [_ v] (.writeLong inner v))
        (writeFloat [_ v] (.writeFloat inner v))
        (writeDouble [_ v] (.writeDouble inner v))
        (writeBytes [_ v] (.writeBytes inner v))
        (writeObject [_ v] (.writeObject inner v))

        (structKeyWriter [_ k] (.structKeyWriter inner k))
        (startStruct [_] (.startStruct inner))
        (endStruct [_] (.endStruct inner))

        (listElementWriter [_] (.listElementWriter inner))
        (startList [_] (.startList inner))
        (endList [_] (.endList inner))

        (registerNewType [_ field] (.registerNewType inner field))
        (writerForType [_ col-type] (.writerForType inner col-type))
        (writerForTypeId [_ type-id] (.writerForTypeId inner type-id))))))

(extend-protocol ArrowWriteable
  Keyword
  (value->col-type [_] :keyword)
  (write-value! [kw ^IVectorWriter w]
    (write-value! (str (symbol kw)) w))

  UUID
  (value->col-type [_] :uuid)
  (write-value! [^UUID uuid ^IVectorWriter w]
    (write-value! (util/uuid->bytes uuid) w))

  URI
  (value->col-type [_] :uri)
  (write-value! [^URI uri ^IVectorWriter w]
    (write-value! (str uri) w))

  ClojureForm
  (value->col-type [_] :clj-form)
  (write-value! [clj-form ^IVectorWriter w]
    (write-value! (pr-str (.form clj-form)) w)))

(defn write-vec! [^ValueVector v, vs]
  (.clear v)

  (let [writer (->writer v)]
    (doseq [v vs]
      (write-value! v (.writerForType writer (value->col-type v))))

    (.syncValueCount writer)

    v))

(defn rows->col-types [rows]
  (->> (for [col-name (into #{} (mapcat keys) rows)]
         [(symbol col-name) (->> rows
                                 (into #{} (map (fn [row]
                                                  (value->col-type (get row col-name)))))
                                 (apply types/merge-col-types))])
       (into {})))


(defn ->vec-writer
  (^xtdb.vector.IVectorWriter [^BufferAllocator allocator, col-name]
   (->writer (-> (types/->field col-name types/dense-union-type false)
                 (.createVector allocator))))

  (^xtdb.vector.IVectorWriter [^BufferAllocator allocator, col-name, col-type]
   (->writer (-> (types/col-type->field col-name col-type)
                 (.createVector allocator)))))

(defn ->rel-copier ^xtdb.vector.IRowCopier [^IRelationWriter rel-wtr, ^RelationReader in-rel]
  (let [wp (.writerPosition rel-wtr)
        copiers (vec (for [^IVectorReader in-vec in-rel]
                       (.rowCopier in-vec (.writerForName rel-wtr (.getName in-vec)))))]
    (reify IRowCopier
      (copyRow [_ src-idx]
        (.startRow rel-wtr)
        (let [pos (.getPosition wp)]
          (doseq [^IRowCopier copier copiers]
            (.copyRow copier src-idx))
          (.endRow rel-wtr)
          pos)))))

(defn ->rel-writer ^xtdb.vector.IRelationWriter [^BufferAllocator allocator]
  (let [writers (LinkedHashMap.)
        wp (IVectorPosition/build)]
    (reify IRelationWriter
      (writerPosition [_] wp)

      (startRow [_])

      (endRow [_]
        (let [pos (.getPositionAndIncrement wp)]
          (doseq [w (vals writers)]
            (populate-with-absents w (inc pos)))))

      (writerForName [_ col-name]
        (.computeIfAbsent writers col-name
                          (reify Function
                            (apply [_ col-name]
                              (doto (->vec-writer allocator col-name)
                                (populate-with-absents (.getPosition wp)))))))

      (writerForName [_ col-name col-type]
        (.computeIfAbsent writers col-name
                          (reify Function
                            (apply [_ col-name]
                              (let [pos (.getPosition wp)]
                                (if (pos? pos)
                                  (doto (->vec-writer allocator col-name (types/merge-col-types col-type :absent))
                                    (populate-with-absents pos))
                                  (->vec-writer allocator col-name col-type)))))))

      (rowCopier [this in-rel] (->rel-copier this in-rel))

      (iterator [_] (.iterator (.entrySet writers)))

      AutoCloseable
      (close [this]
        (run! util/try-close (vals this))))))

(defn root->writer ^xtdb.vector.IRelationWriter [^VectorSchemaRoot root]
  (let [writers (LinkedHashMap.)
        wp (IVectorPosition/build)]
    (doseq [^ValueVector vec (.getFieldVectors root)]
      (.put writers (.getName vec) (->writer vec)))

    (reify IRelationWriter
      (writerPosition [_] wp)

      (startRow [_])
      (endRow [_]
        (let [pos (.getPositionAndIncrement wp)]
          (doseq [^IVectorWriter w (.values writers)]
            (populate-with-absents w (inc pos)))))

      (writerForName [_ col-name]
        (or (.get writers col-name)
            (throw (NullPointerException.))))

      (writerForName [this col-name _col-type]
        (.writerForName this col-name))

      (rowCopier [this in-rel] (->rel-copier this in-rel))

      (iterator [_] (.iterator (.entrySet writers)))

      (syncRowCount [_]
        (.setRowCount root (.getPosition wp))

        (doseq [^IVectorWriter w (vals writers)]
          (.syncValueCount w)))

      AutoCloseable
      (close [this]
        (run! util/try-close (vals this))))))

(defn struct-writer->rel-copier ^xtdb.vector.IRowCopier [^IVectorWriter vec-wtr, ^RelationReader in-rel]
  (let [wp (.writerPosition vec-wtr)
        copiers (for [^IVectorReader src-vec in-rel]
                  (.rowCopier src-vec (.structKeyWriter vec-wtr (.getName src-vec))))]
    (reify IRowCopier
      (copyRow [_ src-idx]
        (let [pos (.getPosition wp)]
          (.startStruct vec-wtr)
          (doseq [^IRowCopier copier copiers]
            (.copyRow copier src-idx))
          (.endStruct vec-wtr)
          pos)))))

(defn open-vec
  (^org.apache.arrow.vector.ValueVector [allocator col-name vs]
   (open-vec allocator col-name
             (->> (into #{} (map value->col-type) vs)
                  (apply types/merge-col-types))
             vs))

  (^org.apache.arrow.vector.ValueVector [allocator col-name col-type vs]
   (util/with-close-on-catch [res (-> (types/col-type->field col-name col-type)
                                      (.createVector allocator))]
     (doto res (write-vec! vs)))))

(defn open-rel ^xtdb.vector.RelationReader [vecs]
  (vr/rel-reader (map vr/vec->reader vecs)))

(defn open-params ^xtdb.vector.RelationReader [allocator params-map]
  (open-rel (for [[k v] params-map]
              (open-vec allocator k [v]))))

(def empty-params (vr/rel-reader [] 1))

(defn vec-wtr->rdr ^xtdb.vector.IVectorReader [^xtdb.vector.IVectorWriter w]
  (vr/vec->reader (.getVector (doto w (.syncValueCount)))))

(defn rel-wtr->rdr ^xtdb.vector.RelationReader [^xtdb.vector.IRelationWriter w]
  (vr/rel-reader (map vec-wtr->rdr (vals w))
                 (.getPosition (.writerPosition w))))

(defn append-vec [^IVectorWriter vec-writer, ^IVectorReader in-col]
  (let [row-copier (.rowCopier in-col vec-writer)]
    (dotimes [src-idx (.valueCount in-col)]
      (.copyRow row-copier src-idx))))

(defn append-rel [^IRelationWriter dest-rel, ^RelationReader src-rel]
  (doseq [^IVectorReader src-col src-rel
          :let [col-type (types/field->col-type (.getField src-col))
                ^IVectorWriter vec-writer (.writerForName dest-rel (.getName src-col) col-type)]]
    (append-vec vec-writer src-col))

  (let [wp (.writerPosition dest-rel)]
    (.setPosition wp (+ (.getPosition wp) (.rowCount src-rel)))))
