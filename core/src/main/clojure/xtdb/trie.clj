(ns xtdb.trie
  (:require [xtdb.buffer-pool]
            [xtdb.metadata :as meta]
            [xtdb.object-store]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import (java.lang AutoCloseable)
           (java.nio ByteBuffer)
           java.nio.channels.WritableByteChannel
           java.security.MessageDigest
           (java.util Arrays)
           (java.util.concurrent.atomic AtomicInteger)
           (java.util.function IntConsumer Supplier)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.vector VectorSchemaRoot)
           [org.apache.arrow.vector.ipc ArrowFileWriter]
           (org.apache.arrow.vector.types.pojo ArrowType$Union Schema)
           (org.apache.arrow.vector.types.pojo ArrowType$Union Schema)
           org.apache.arrow.vector.types.UnionMode
           (xtdb.object_store ObjectStore)
           (xtdb.trie ArrowHashTrie$Leaf HashTrie HashTrie$Node LiveHashTrie LiveHashTrie$Leaf)
           (xtdb.util WritableByteBufferChannel)
           (xtdb.vector IVectorReader RelationReader)))

(def ^:private ^java.lang.ThreadLocal !msg-digest
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_]
       (MessageDigest/getInstance "SHA-256")))))

(defn ->iid ^ByteBuffer [eid]
  (if (uuid? eid)
    (util/uuid->byte-buffer eid)
    (ByteBuffer/wrap
     (let [^bytes eid-bytes (cond
                              (string? eid) (.getBytes (str "s" eid))
                              (keyword? eid) (.getBytes (str "k" eid))
                              (integer? eid) (.getBytes (str "i" eid))
                              :else (throw (UnsupportedOperationException. (pr-str (class eid)))))]
       (-> ^MessageDigest (.get !msg-digest)
           (.digest eid-bytes)
           (Arrays/copyOfRange 0 16))))))

(def ^org.apache.arrow.vector.types.pojo.Schema trie-schema
  (Schema. [(types/->field "nodes" (ArrowType$Union. UnionMode/Dense (int-array (range 3))) false
                           (types/col-type->field "nil" :null)
                           (types/col-type->field "branch" [:list [:union #{:null :i32}]])
                           (types/col-type->field "leaf" [:struct {'page-idx :i32
                                                                   'columns meta/metadata-col-type}]))]))

(def put-field
  (types/col-type->field "put" [:struct {'xt$valid_from types/temporal-col-type
                                         'xt$valid_to types/temporal-col-type
                                         'xt$doc [:union #{:null [:struct {}]}]}]))

(def delete-field
  (types/col-type->field "delete" [:struct {'xt$valid_from types/temporal-col-type
                                            'xt$valid_to types/temporal-col-type}]))

(def evict-field
  (types/col-type->field "evict" :null))

(def ^org.apache.arrow.vector.types.pojo.Schema log-leaf-schema
  (Schema. [(types/col-type->field "xt$iid" [:fixed-size-binary 16])
            (types/col-type->field "xt$system_from" types/temporal-col-type)
            (types/->field "op" (ArrowType$Union. UnionMode/Dense (int-array (range 3))) false
                           put-field delete-field evict-field)]))

(defn open-leaf-root ^xtdb.vector.IRelationWriter [^BufferAllocator allocator]
  (util/with-close-on-catch [root (VectorSchemaRoot/create log-leaf-schema allocator)]
    (vw/root->writer root)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ITrieWriter
  (^xtdb.vector.IRelationWriter getLeafWriter [])
  (^int writeLeaf [])
  (^int writeBranch [^ints idxs])
  (^void end [])
  (^void close []))

(defn open-trie-writer ^xtdb.trie.ITrieWriter [^BufferAllocator allocator, ^Schema leaf-schema
                                               ^WritableByteChannel leaf-out-ch, ^WritableByteChannel trie-out-ch]
  (util/with-close-on-catch [leaf-vsr (VectorSchemaRoot/create leaf-schema allocator)
                             leaf-out-wtr (ArrowFileWriter. leaf-vsr nil leaf-out-ch)
                             trie-vsr (VectorSchemaRoot/create trie-schema allocator)]
    (.start leaf-out-wtr)
    (let [leaf-rel-wtr (vw/root->writer leaf-vsr)
          trie-rel-wtr (vw/root->writer trie-vsr)

          node-wtr (.writerForName trie-rel-wtr "nodes")
          node-wp (.writerPosition node-wtr)

          branch-wtr (.writerForTypeId node-wtr (byte 1))
          branch-el-wtr (.listElementWriter branch-wtr)

          leaf-wtr (.writerForTypeId node-wtr (byte 2))
          page-idx-wtr (.structKeyWriter leaf-wtr "page-idx")
          page-meta-wtr (meta/->page-meta-wtr (.structKeyWriter leaf-wtr "columns"))
          !page-idx (AtomicInteger. 0)]

      (reify ITrieWriter
        (getLeafWriter [_] leaf-rel-wtr)

        (writeLeaf [_]
          (.syncRowCount leaf-rel-wtr)

          (let [leaf-rdr (vw/rel-wtr->rdr leaf-rel-wtr)
                put-rdr (-> leaf-rdr
                            (.readerForName "op")
                            (.legReader :put)
                            (.metadataReader))

                doc-rdr (.structKeyReader put-rdr "xt$doc")]

            (.writeMetadata page-meta-wtr (into [(.readerForName leaf-rdr "xt$system_from")
                                                 (.readerForName leaf-rdr "xt$iid")
                                                 (.structKeyReader put-rdr "xt$valid_from")
                                                 (.structKeyReader put-rdr "xt$valid_to")]
                                                (map #(.structKeyReader doc-rdr %))
                                                (.structKeys doc-rdr))))

          (.writeBatch leaf-out-wtr)
          (.clear leaf-rel-wtr)
          (.clear leaf-vsr)

          (let [pos (.getPosition node-wp)]
            (.startStruct leaf-wtr)
            (.writeInt page-idx-wtr (.getAndIncrement !page-idx))
            (.endStruct leaf-wtr)
            (.endRow trie-rel-wtr)

            pos))

        (writeBranch [_ idxs]
          (let [pos (.getPosition node-wp)]
            (.startList branch-wtr)

            (dotimes [n (alength idxs)]
              (let [idx (aget idxs n)]
                (if (= idx -1)
                  (.writeNull branch-el-wtr nil)
                  (.writeInt branch-el-wtr idx))))

            (.endList branch-wtr)
            (.endRow trie-rel-wtr)

            pos))

        (end [_]
          (.end leaf-out-wtr)

          (.syncSchema trie-vsr)
          (.syncRowCount trie-rel-wtr)

          (util/with-open [trie-out-wtr (ArrowFileWriter. trie-vsr nil trie-out-ch)]
            (.start trie-out-wtr)
            (.writeBatch trie-out-wtr)
            (.end trie-out-wtr)))

        AutoCloseable
        (close [_]
          (util/close [trie-vsr leaf-out-wtr leaf-vsr]))))))

(defn write-live-trie [^ITrieWriter trie-wtr, ^LiveHashTrie trie, ^RelationReader leaf-rel]
  (let [trie (.compactLogs trie)
        copier (vw/->rel-copier (.getLeafWriter trie-wtr) leaf-rel)]
    (letfn [(write-node! [^HashTrie$Node node]
              (if-let [children (.children node)]
                (let [child-count (alength children)
                      !idxs (int-array child-count)]
                  (dotimes [n child-count]
                    (aset !idxs n
                          (unchecked-int
                           (if-let [child (aget children n)]
                             (write-node! child)
                             -1))))

                  (.writeBranch trie-wtr !idxs))

                (let [^LiveHashTrie$Leaf leaf node]
                  (-> (Arrays/stream (.data leaf))
                      (.forEach (reify IntConsumer
                                  (accept [_ idx]
                                    (.copyRow copier idx)))))

                  (.writeLeaf trie-wtr))))]

      (write-node! (.rootNode trie)))))

(defn live-trie->bufs [^BufferAllocator allocator, ^LiveHashTrie trie, ^RelationReader leaf-rel]
  (util/with-open [leaf-bb-ch (WritableByteBufferChannel/open)
                   trie-bb-ch (WritableByteBufferChannel/open)
                   trie-wtr (open-trie-writer allocator
                                              (Schema. (for [^IVectorReader rdr leaf-rel]
                                                         (.getField rdr)))
                                              (.getChannel leaf-bb-ch)
                                              (.getChannel trie-bb-ch))]

    (write-live-trie trie-wtr trie leaf-rel)

    (.end trie-wtr)

    {:leaf-buf (.getAsByteBuffer leaf-bb-ch)
     :trie-buf (.getAsByteBuffer trie-bb-ch)}))

(defn write-trie-bufs! [^ObjectStore obj-store, ^String dir, ^String chunk-idx
                        {:keys [^ByteBuffer leaf-buf ^ByteBuffer trie-buf]}]
  (-> (.putObject obj-store (format "%s/leaf-c%s.arrow" dir chunk-idx) leaf-buf)
      (util/then-compose
        (fn [_]
          (.putObject obj-store (format "%s/trie-c%s.arrow" dir chunk-idx) trie-buf)))))

(defn- bucket-for [^ByteBuffer iid level]
  (let [level-offset-bits (* HashTrie/LEVEL_BITS (inc level))
        level-offset-bytes (/ (- level-offset-bits HashTrie/LEVEL_BITS) Byte/SIZE)]
    (bit-and (bit-shift-right (.get iid ^int level-offset-bytes) (mod level-offset-bits Byte/SIZE)) HashTrie/LEVEL_MASK)))

(defn ->merge-plan
  "Returns a tree of the tasks required to merge the given tries"
  [tries, ^ByteBuffer iid]

  (letfn [(->merge-plan* [nodes path ^long level]
            (let [trie-children (mapv #(some-> ^HashTrie$Node % (.children)) nodes)]
              (if-let [^objects first-children (some identity trie-children)]
                {:path (byte-array path)
                 :node [:branch (->> (if iid
                                       [(bucket-for iid level)]
                                       (range (alength first-children)))
                                     (mapv (fn [bucket-idx]
                                             (->merge-plan* (mapv (fn [node ^objects node-children]
                                                                          (if node-children
                                                                            (aget node-children bucket-idx)
                                                                            node))
                                                                        nodes trie-children)
                                                                  (conj path bucket-idx)
                                                                  (inc level)))))]}
                {:path (byte-array path)
                 :node [:leaf (->> nodes
                                   (into [] (keep-indexed
                                             (fn [ordinal ^HashTrie$Node leaf-node]
                                               (condp = (class leaf-node)
                                                 ArrowHashTrie$Leaf {:ordinal ordinal,
                                                                     :trie-leaf {:page-idx (.getPageIndex ^ArrowHashTrie$Leaf leaf-node)}}

                                                 LiveHashTrie$Leaf {:ordinal ordinal, :trie-leaf leaf-node})))))]})))]

    (->merge-plan* (map #(some-> ^HashTrie % (.rootNode)) tries) [] 0)))
