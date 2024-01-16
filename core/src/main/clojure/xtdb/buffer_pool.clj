(ns xtdb.buffer-pool
  (:require [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.object-store :as os]
            [xtdb.util :as util]
            [xtdb.node :as xtn])
  (:import (clojure.lang PersistentQueue)
           [java.io ByteArrayOutputStream Closeable]
           (java.nio ByteBuffer)
           (java.nio.channels Channels)
           [java.nio.file FileVisitOption Files LinkOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Map NavigableMap TreeMap]
           [java.util.concurrent CompletableFuture]
           (java.util.concurrent.atomic AtomicLong)
           java.util.NavigableMap
           [org.apache.arrow.memory ArrowBuf BufferAllocator]
           (org.apache.arrow.vector VectorSchemaRoot)
           (org.apache.arrow.vector.ipc ArrowFileWriter)
           (org.apache.arrow.vector.ipc.message ArrowBlock ArrowFooter ArrowRecordBatch)
           (xtdb IArrowWriter IBufferPool)
           xtdb.api.Xtdb$Config
           (xtdb.api.storage Storage LocalStorageFactory ObjectStore RemoteStorageFactory StorageFactory)
           (xtdb.multipart IMultipartUpload SupportsMultipart)
           xtdb.util.ArrowBufLRU))

(set! *unchecked-math* :warn-on-boxed)

(def ^:private min-multipart-part-size (* 5 1024 1024))
(def ^:private max-multipart-per-upload-concurrency 4)

(defn- free-memory [^Map memory-store]
  (locking memory-store
    (run! util/close (.values memory-store))
    (.clear memory-store)))

(defn- retain [^ArrowBuf buf] (.retain (.getReferenceManager buf)) buf)

(defn- cache-get ^ArrowBuf [^Map memory-store k]
  (locking memory-store
    (some-> (.get memory-store k) retain)))

(def ^AtomicLong cache-miss-byte-counter (AtomicLong.))
(def ^AtomicLong cache-hit-byte-counter (AtomicLong.))
(def io-wait-nanos-counter (atom 0N))

(defn clear-cache-counters []
  (.set cache-miss-byte-counter 0)
  (.set cache-hit-byte-counter 0)
  (reset! io-wait-nanos-counter 0N))

(defn- record-cache-miss [^ArrowBuf arrow-buf]
  (.addAndGet cache-miss-byte-counter (.capacity arrow-buf)))

(defn- record-cache-hit [^ArrowBuf arrow-buf]
  (.addAndGet cache-hit-byte-counter (.capacity arrow-buf)))

(defn- record-io-wait [^long start-ns]
  (swap! io-wait-nanos-counter +' (- (System/nanoTime) start-ns)))

(defn- cache-compute
  "Returns a pair [hit-or-miss, buf] computing the cached ArrowBuf from (f) if needed.
  `hit-or-miss` is true if the buffer was found, false if the object was added as part of this call."
  [^Map memory-store k f]
  (locking memory-store
    (let [hit (.containsKey memory-store k)
          arrow-buf (if hit (.get memory-store k) (let [buf (f)] (.put memory-store k buf) buf))]
      (if hit (record-cache-hit arrow-buf) (record-cache-miss arrow-buf))
      [hit (retain arrow-buf)])))

(defrecord MemoryBufferPool [allocator, ^NavigableMap memory-store]
  IBufferPool
  (getBuffer [_ k]
    (let [cached-buffer (cache-get memory-store k)]
      (cond
        (nil? k)
        (CompletableFuture/completedFuture nil)

        cached-buffer
        (do (record-cache-hit cached-buffer)
            (CompletableFuture/completedFuture cached-buffer))

        :else
        (CompletableFuture/failedFuture (os/obj-missing-exception k)))))

  (putObject [_ k buffer]
    (CompletableFuture/completedFuture
     (locking memory-store
       (.put memory-store k (util/->arrow-buf-view allocator buffer)))))

  (listObjects [_]
    (locking memory-store (vec (.keySet ^NavigableMap memory-store))))

  (listObjects [_ dir]
    (locking memory-store
      (let [dir-depth (.getNameCount dir)]
        (->> (.keySet (.tailMap ^NavigableMap memory-store dir))
             (take-while #(.startsWith ^Path % dir))
             (keep (fn [^Path path]
                     (when (> (.getNameCount path) dir-depth)
                       (.subpath path 0 (inc dir-depth)))))
             (distinct)
             (vec)))))

  (openArrowWriter [this k vsr]
    (let [baos (ByteArrayOutputStream.)
          write-ch (Channels/newChannel baos)
          aw (ArrowFileWriter. vsr nil write-ch)]

      (.start aw)

      (reify IArrowWriter
        (writeBatch [_] (.writeBatch aw))

        (end [_]
          (.end aw)
          (.close write-ch)
          (.putObject this k (ByteBuffer/wrap (.toByteArray baos))))

        (close [_]
          (.close aw)
          (when (.isOpen write-ch)
            (.close write-ch))))))

  Closeable
  (close [_]
    (free-memory memory-store)
    (util/close allocator)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn open-in-memory-storage [^BufferAllocator allocator]
  (->MemoryBufferPool (.newChildAllocator allocator "buffer-pool" 0 Long/MAX_VALUE)
                      (TreeMap.)))

(defn- create-tmp-path ^Path [^Path disk-store]
  (Files/createTempFile (doto (.resolve disk-store ".tmp") util/mkdirs)
                        "upload" ".arrow"
                        (make-array FileAttribute 0)))

(defrecord LocalBufferPool [allocator, ^ArrowBufLRU memory-store, ^Path disk-store]
  IBufferPool
  (getBuffer [_ k]
    (let [cached-buffer (cache-get memory-store k)]
      (cond
        (nil? k)
        (CompletableFuture/completedFuture nil)

        cached-buffer
        (do (record-cache-hit cached-buffer)
            (CompletableFuture/completedFuture cached-buffer))

        :else
        (let [buffer-cache-path (.resolve disk-store (str k))]
          (-> (if (util/path-exists buffer-cache-path)
                ;; todo could this not race with eviction? e.g exists for this cond, but is evicted before we can map the file into the cache?
                (CompletableFuture/completedFuture buffer-cache-path)
                (CompletableFuture/failedFuture (os/obj-missing-exception k)))
              (util/then-apply
                (fn [path]
                  (let [nio-buffer (util/->mmap-path path)
                        create-arrow-buf #(util/->arrow-buf-view allocator nio-buffer)
                        [_ buf] (cache-compute memory-store k create-arrow-buf)]
                    buf))))))))

  (putObject [_ k buffer]
    (CompletableFuture/completedFuture
     (let [tmp-path (create-tmp-path disk-store)]
       (util/write-buffer-to-path buffer tmp-path)

       (let [file-path (.resolve disk-store k)]
         (util/create-parents file-path)
         (util/atomic-move tmp-path file-path)))))

  (listObjects [_]
    (with-open [dir-stream (Files/walk disk-store (make-array FileVisitOption 0))]
      (vec (sort (for [^Path path (iterator-seq (.iterator dir-stream))
                       :when (Files/isRegularFile path (make-array LinkOption 0))]
                   (.relativize disk-store path))))))

  (listObjects [_ dir]
    (let [dir (.resolve disk-store dir)]
      (when (Files/exists dir (make-array LinkOption 0))
        (with-open [dir-stream (Files/newDirectoryStream dir)]
          (vec (sort (for [^Path path dir-stream]
                       (.relativize disk-store path))))))))

  (openArrowWriter [_ k vsr]
    (let [tmp-path (create-tmp-path disk-store)
          file-ch (util/->file-channel tmp-path util/write-truncate-open-opts)
          aw (ArrowFileWriter. vsr nil file-ch)]
      (.start aw)
      (reify IArrowWriter
        (writeBatch [_] (.writeBatch aw))

        (end [_]
          (.end aw)
          (.close file-ch)

          (let [file-path (.resolve disk-store k)]
            (util/create-parents file-path)
            (util/atomic-move tmp-path file-path)))

        (close [_]
          (util/close aw)
          (when (.isOpen file-ch)
            (.close file-ch))))))

  Closeable
  (close [_]
    (free-memory memory-store)
    (util/close allocator)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn open-local-storage ^xtdb.IBufferPool [^BufferAllocator allocator, ^LocalStorageFactory factory]
  (->LocalBufferPool (.newChildAllocator allocator "buffer-pool" 0 Long/MAX_VALUE)
                     (ArrowBufLRU. 16 (.getMaxCacheEntries factory) (.getMaxCacheBytes factory))
                     (.getDataDirectory factory)))

(defmethod xtn/apply-config! :xtdb.buffer-pool/local [^Xtdb$Config config _ {:keys [data-dir max-cache-bytes max-cache-entries]}]
  (.storage config (cond-> (Storage/local data-dir)
                     max-cache-bytes (.maxCacheBytes max-cache-bytes)
                     max-cache-entries (.maxCacheEntries max-cache-entries))))

(defn- upload-multipart-buffers [object-store k nio-buffers]
  (let [^IMultipartUpload upload @(.startMultipart ^SupportsMultipart object-store k)]
    (try

      (loop [part-queue (into PersistentQueue/EMPTY nio-buffers)
             waiting-parts []]
        (cond
          (empty? part-queue) @(CompletableFuture/allOf (into-array CompletableFuture waiting-parts))

          (< (count waiting-parts) (int max-multipart-per-upload-concurrency))
          (recur (pop part-queue) (conj waiting-parts (.uploadPart upload ^ByteBuffer (peek part-queue))))

          :else
          (do @(CompletableFuture/anyOf (into-array CompletableFuture waiting-parts))
              (recur part-queue (vec (remove future-done? waiting-parts))))))

      @(.complete upload)

      (catch Throwable upload-loop-t
        (try
          @(.abort upload)
          (catch Throwable abort-t
            (.addSuppressed upload-loop-t abort-t)))
        (throw upload-loop-t)))))

(defn- arrow-buf-cuts [^ArrowBuf arrow-buf]
  (loop [cuts []
         prev-cut (int 0)
         cut (int 0)
         blocks (.getRecordBatches (util/read-arrow-footer arrow-buf))]
    (if-some [[^ArrowBlock block & blocks] (seq blocks)]
      (let [offset (.getOffset block)
            offset-delta (- offset cut)
            metadata-length (.getMetadataLength block)
            body-length (.getBodyLength block)
            total-length (+ offset-delta metadata-length body-length)
            new-cut (+ cut total-length)
            cut-len (- new-cut prev-cut)]
        (if (<= (int min-multipart-part-size) cut-len)
          (recur (conj cuts new-cut) new-cut new-cut blocks)
          (recur cuts prev-cut new-cut blocks)))
      cuts)))

(defn- arrow-buf->parts [^ArrowBuf arrow-buf]
  (loop [part-buffers []
         prev-cut (int 0)
         cuts (arrow-buf-cuts arrow-buf)]
    (if-some [[cut & cuts] (seq cuts)]
      (recur (conj part-buffers (.nioBuffer arrow-buf prev-cut (- (int cut) prev-cut))) (int cut) cuts)
      (let [final-part (.nioBuffer arrow-buf prev-cut (- (.capacity arrow-buf) prev-cut))]
        (conj part-buffers final-part)))))

(defn- upload-arrow-file [^BufferAllocator allocator, ^ObjectStore remote-store, ^Path k, ^Path tmp-path]
  (let [mmap-buffer (util/->mmap-path tmp-path)]
    (if (or (not (instance? SupportsMultipart remote-store))
            (<= (.remaining mmap-buffer) (int min-multipart-part-size)))
      @(.putObject remote-store k mmap-buffer)

      (with-open [arrow-buf (util/->arrow-buf-view allocator mmap-buffer)]
        (upload-multipart-buffers remote-store k (arrow-buf->parts arrow-buf))
        nil))))

(defrecord RemoteBufferPool [allocator
                             ^ArrowBufLRU memory-store
                             ^Path disk-store
                             ^ObjectStore remote-store]
  IBufferPool
  (getBuffer [_ k]
    (let [cached-buffer (cache-get memory-store k)]
      (cond
        (nil? k)
        (CompletableFuture/completedFuture nil)

        cached-buffer
        (do (record-cache-hit cached-buffer)
            (CompletableFuture/completedFuture cached-buffer))

        :else
        (let [buffer-cache-path (.resolve disk-store (str k))
              start-ns (System/nanoTime)]
          (-> (if (util/path-exists buffer-cache-path)
                ;; todo could this not race with eviction? e.g exists for this cond, but is evicted before we can map the file into the cache?
                (CompletableFuture/completedFuture buffer-cache-path)
                (do (util/create-parents buffer-cache-path)
                    (-> (.getObject remote-store k buffer-cache-path)
                        (util/then-apply (fn [path] (record-io-wait start-ns) path)))))
              (util/then-apply
                (fn [path]
                  (let [nio-buffer (util/->mmap-path path)
                        close-fn (fn [] (util/delete-file path))
                        create-arrow-buf #(util/->arrow-buf-view allocator nio-buffer close-fn)
                        [_ buf] (cache-compute memory-store k create-arrow-buf)]
                    buf))))))))

  (listObjects [_] (.listObjects remote-store))

  (listObjects [_ dir] (.listObjects remote-store dir))

  (openArrowWriter [_ k vsr]
    (let [tmp-path (create-tmp-path disk-store)
          file-ch (util/->file-channel tmp-path util/write-truncate-open-opts)
          aw (ArrowFileWriter. vsr nil file-ch)]

      (.start aw)

      (reify IArrowWriter
        (writeBatch [_] (.writeBatch aw))

        (end [_]
          (.end aw)
          (.close file-ch)

          (upload-arrow-file allocator remote-store k tmp-path)

          (let [file-path (.resolve disk-store k)]
            (util/create-parents file-path)
            ;; see #2847
            (util/atomic-move tmp-path file-path)))

        (close [_]
          (util/close aw)

          (when (.isOpen file-ch)
            (.close file-ch))))))

  (putObject [_ k buffer]
    (if (or (not (instance? SupportsMultipart remote-store))
            (<= (.remaining buffer) (int min-multipart-part-size)))
      (.putObject remote-store k buffer)

      (let [buffers (->> (range (.position buffer) (.limit buffer) min-multipart-part-size)
                         (map (fn [n] (.slice buffer
                                              (int n)
                                              (min (int min-multipart-part-size)
                                                   (- (.limit buffer) (int n)))))))]
        (-> (CompletableFuture/runAsync
             (fn []
               (upload-multipart-buffers remote-store k buffers)))

            (.thenRun (fn []
                        (let [tmp-path (create-tmp-path disk-store)]
                          (with-open [file-ch (util/->file-channel tmp-path util/write-truncate-open-opts)]
                            (.write file-ch buffer))

                          (let [file-path (.resolve disk-store k)]
                            (util/create-parents file-path)
                            ;; see #2847
                            (util/atomic-move tmp-path file-path)))))))))

  Closeable
  (close [_]
    (free-memory memory-store)
    (util/close remote-store)
    (util/close allocator)))

(set! *unchecked-math* :warn-on-boxed)

(defn open-remote-storage ^xtdb.IBufferPool [^BufferAllocator allocator, ^RemoteStorageFactory factory]
  (util/with-close-on-catch [object-store (.openObjectStore (.getObjectStore factory))]
    (->RemoteBufferPool (.newChildAllocator allocator "buffer-pool" 0 Long/MAX_VALUE)
                        (ArrowBufLRU. 16 (.getMaxCacheEntries factory) (.getMaxCacheBytes factory))
                        (.getDiskStore factory)
                        object-store)))

(defmulti ->object-store-factory
  #_{:clj-kondo/ignore [:unused-binding]}
  (fn [tag opts]
    (when-let [ns (namespace tag)]
      (doseq [k [(symbol ns)
                 (symbol (str ns "." (name tag)))]]

        (try
          (require k)
          (catch Throwable _))))

    tag))

(defmethod xtn/apply-config! :xtdb.buffer-pool/remote [^Xtdb$Config config _ {:keys [object-store disk-store max-cache-bytes max-cache-entries]}]
  (.storage config (cond-> (Storage/remote (let [[tag opts] object-store]
                                             (->object-store-factory tag opts))
                                           disk-store)
                     max-cache-bytes (.maxCacheBytes max-cache-bytes)
                     max-cache-entries (.maxCacheEntries max-cache-entries))))

(defn get-footer ^ArrowFooter [^IBufferPool bp ^Path path]
  (with-open [^ArrowBuf arrow-buf @(.getBuffer bp path)]
    (util/read-arrow-footer arrow-buf)))

(defn open-record-batch ^ArrowRecordBatch [^IBufferPool bp ^Path path block-idx]
  (with-open [^ArrowBuf arrow-buf @(.getBuffer bp path)]
    (let [footer (util/read-arrow-footer arrow-buf)
          blocks (.getRecordBatches footer)
          block (nth blocks block-idx nil)]
      (if-not block
        (throw (IndexOutOfBoundsException. "Record batch index out of bounds of arrow file"))
        (util/->arrow-record-batch-view block arrow-buf)))))

(defn open-vsr ^VectorSchemaRoot [bp ^Path path allocator]
  (let [footer (get-footer bp path)
        schema (.getSchema footer)]
    (VectorSchemaRoot/create schema allocator)))

(defmethod ig/prep-key :xtdb/buffer-pool [_ factory]
  {:allocator (ig/ref :xtdb/allocator)
   :factory factory})

(defmethod ig/init-key :xtdb/buffer-pool [_ {:keys [allocator ^StorageFactory factory]}]
  (.openStorage factory allocator))

(defmethod ig/halt-key! :xtdb/buffer-pool [_ ^IBufferPool buffer-pool]
  (util/close buffer-pool))
