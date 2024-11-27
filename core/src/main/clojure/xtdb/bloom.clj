(ns xtdb.bloom
  (:require [xtdb.expression :as expr]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr])
  (:import com.carrotsearch.hppc.IntArrayList
           java.nio.ByteBuffer
           (org.apache.arrow.memory RootAllocator)
           (org.apache.arrow.memory.util ArrowBufPointer)
           (org.apache.arrow.memory.util.hash MurmurHasher SimpleHasher)
           [org.apache.arrow.vector ValueVector]
           (org.roaringbitmap RoaringBitmap)
           org.roaringbitmap.buffer.ImmutableRoaringBitmap
           (xtdb.arrow RelationReader VectorReader VectorWriter)))

(set! *unchecked-math* :warn-on-boxed)

;; max-rows  bloom-bits  false-positive
;; 1000      13           0.0288
;; 10000     16           0.0495
;; 10000     17           0.0085
;; 100000    20           0.0154

(def ^:const ^:private default-bloom-bits (bit-shift-left 1 20))

(defn- init-bloom-bits ^long []
  (let [bloom-bits (Long/getLong "xtdb.bloom.bits" default-bloom-bits)]
    (assert (= 1 (Long/bitCount bloom-bits)))
    bloom-bits))

(def ^:const bloom-bits (init-bloom-bits))
(def ^:const bloom-bit-mask (dec bloom-bits))
(def ^:const bloom-k 3)

(comment
  (defn bloom-false-positive-probability?
    (^double [^long n]
     (bloom-false-positive-probability? n bloom-k bloom-bits))
    (^double [^long n ^long k ^long m]
     (Math/pow (- 1 (Math/exp (/ (- k) (double (/ m n))))) k))))

(defn bloom->bitmap ^org.roaringbitmap.buffer.ImmutableRoaringBitmap [^VectorReader bloom-rdr ^long idx]
  (let [pointer (.getPointer bloom-rdr idx (ArrowBufPointer.))
        nio-buffer (.nioBuffer (.getBuf pointer) (.getOffset pointer) (.getLength pointer))]
    (ImmutableRoaringBitmap. nio-buffer)))

(defn bloom-contains? [^VectorReader bloom-rdr
                       ^long idx
                       ^ints hashes]
  (let [^ImmutableRoaringBitmap bloom (bloom->bitmap bloom-rdr idx)]
    (loop [n 0]
      (if (= n (alength hashes))
        true
        (if (.contains bloom (aget hashes n))
          (recur (inc n))
          false)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface BloomBuilder
  (^void add [^long idx])
  (^org.roaringbitmap.RoaringBitmap build []))

(defn- ->bloom-builder
  (^xtdb.bloom.BloomBuilder [^VectorReader col] (->bloom-builder col bloom-k bloom-bit-mask))
  (^xtdb.bloom.BloomBuilder [^VectorReader col, ^long k, ^long mask]
   (let [buf (IntArrayList. (* (.getValueCount col) k))]
     (reify BloomBuilder
       (add [_ idx]
         (let [hash-1 (.hashCode col idx SimpleHasher/INSTANCE)
               hash-2 (.hashCode col idx (MurmurHasher. hash-1))]
           (dotimes [n k]
             (.add buf (unchecked-int (bit-and mask (+ hash-1 (* hash-2 n))))))))

       (build [_]
         (RoaringBitmap/bitmapOfUnordered (.toArray buf)))))))

;; Cassandra-style hashes:
;; https://www.researchgate.net/publication/220770131_Less_Hashing_Same_Performance_Building_a_Better_Bloom_Filter
(defn bloom-hashes
  (^ints [^VectorReader col ^long idx]
   (bloom-hashes col idx bloom-k bloom-bit-mask))
  (^ints [^VectorReader col ^long idx ^long k ^long mask]
   (let [hash-1 (.hashCode col idx SimpleHasher/INSTANCE)
         hash-2 (.hashCode col idx (MurmurHasher. hash-1))
         acc (int-array k)]
     (dotimes [n k]
       (aset acc n (unchecked-int (bit-and mask (+ hash-1 (* hash-2 n))))))
     acc)))

(def literal-hasher
  (-> (fn [value-expr target-col-type]
        (let [{:keys [return-type continue] :as emitted-expr}
              (expr/codegen-expr {:op :call
                                  :f :cast
                                  :args [value-expr]
                                  :target-type target-col-type}
                                 {:param-types (when (= :param (:op value-expr))
                                                 (let [{:keys [param param-type]} value-expr]
                                                   {param param-type}))})
              {:keys [writer-bindings write-value-out!]} (expr/write-value-out-code return-type)]
          (-> `(fn [~(-> expr/args-sym (expr/with-tag RelationReader))
                    ~(-> expr/out-vec-sym (expr/with-tag ValueVector))]
                 (let [~@(expr/batch-bindings emitted-expr)
                       ~@writer-bindings]
                   ~(continue (fn [return-type code]
                                `(do
                                   ~(write-value-out! return-type code)
                                   (bloom-hashes (VectorReader/from (vr/vec->reader ~expr/out-vec-sym)) 0))))))
              #_(doto (clojure.pprint/pprint))
              (eval))))
      (util/lru-memoize)))

(defn literal-hashes ^ints [params value-expr target-col-type]
  (let [f (literal-hasher value-expr target-col-type)]
    (with-open [allocator (RootAllocator.)
                tmp-vec (-> (types/col-type->field target-col-type)
                            (.createVector allocator))]
      (f params tmp-vec))))

(defn write-bloom [^VectorWriter bloom-wtr, ^VectorReader col]
  (let [bloom-builder (->bloom-builder col)]
    (dotimes [in-idx (.getValueCount col)]
      (when-not (.isNull col in-idx)
        (.add bloom-builder in-idx)))

    (let [bloom (.build bloom-builder)
          buf (ByteBuffer/allocate (.serializedSizeInBytes bloom))]
      (.serialize bloom buf)
      (.writeBytes bloom-wtr (doto buf .clear)))))
