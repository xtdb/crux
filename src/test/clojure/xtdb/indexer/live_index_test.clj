(ns xtdb.indexer.live-index-test
  (:require [clojure.test :as t]
            [xtdb.api.protocols :as xtp]
            xtdb.indexer.live-index
            xtdb.object-store
            [xtdb.test-util :as tu]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import [java.util Random UUID]
           [org.apache.arrow.memory BufferAllocator]
           [org.apache.arrow.vector ValueVector]
           [org.apache.arrow.vector.ipc ArrowFileReader]
           xtdb.indexer.live_index.ILiveIndex
           xtdb.object_store.ObjectStore
           (xtdb.trie ArrowHashTrie HashTrie HashTrie$Visitor)))

(def with-live-index
  (tu/with-system {:xtdb/allocator {}
                   :xtdb.indexer/live-index {}
                   :xtdb.object-store/memory-object-store {}}))

(t/use-fixtures :each with-live-index)

(defn- render-trie [^HashTrie ht, ^ValueVector iid-vec]
  (.accept ht
           (reify HashTrie$Visitor
             (visitBranch [visitor children]
               (mapcat #(.accept ^HashTrie % visitor) children))

             (visitLeaf [_ leaf]
               (->> (.toArray (.indices leaf))
                    (mapv #(vec (.getObject iid-vec %))))))))

(t/deftest test-t1-chunk
  (let [{^BufferAllocator allocator :xtdb/allocator
         ^ILiveIndex live-index :xtdb.indexer/live-index
         ^ObjectStore obj-store :xtdb.object-store/memory-object-store} tu/*sys*

        iids (let [rnd (Random. 0)]
               (repeatedly 12000 #(UUID. (.nextLong rnd) (.nextLong rnd))))

        iid-bytes (->> (sort-by #(.getMostSignificantBits ^UUID %) #(Long/compareUnsigned %1 %2) iids)
                       (mapv (comp vec util/uuid->bytes)))]

    (t/testing "commit"
      (util/with-open [live-tx (.startTx live-index (xtp/->TransactionInstant 0 (.toInstant #inst "2020")))]
        (let [live-table-tx (.liveTable live-tx "foo")
              wtr (.writer live-table-tx)
              iid-wtr (.writerForName wtr "xt$iid")]

          (doseq [iid iids]
            (vw/write-value! iid iid-wtr)
            (.endRow wtr))

          (.commit live-table-tx)

          (let [{:keys [static-rel !static-tries]} live-table-tx
                iid-vec (-> (vw/rel-wtr->rdr static-rel)
                            (.vectorForName "xt$iid")
                            (.getVector))
                {:keys [^HashTrie t1]} @!static-tries]

            (t/is (= iid-bytes (render-trie t1 iid-vec)))))))

    (t/testing "finish chunk"
      (.finishChunk live-index 0)

      (let [trie-buf @(.getObject obj-store "tables/foo/t1-diff/trie-c00.arrow")
            leaf-buf @(.getObject obj-store "tables/foo/t1-diff/leaf-c00.arrow")]
        (with-open [trie-rdr (ArrowFileReader. (util/->seekable-byte-channel trie-buf) allocator)
                    leaf-rdr (ArrowFileReader. (util/->seekable-byte-channel leaf-buf) allocator)]
          (.loadNextBatch trie-rdr)
          (t/is (= iid-bytes
                   (render-trie (ArrowHashTrie/from (.getVectorSchemaRoot trie-rdr) leaf-rdr)
                                (.getVector (.getVectorSchemaRoot leaf-rdr) "xt$iid")))))))))
