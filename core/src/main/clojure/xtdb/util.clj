(ns xtdb.util
  (:refer-clojure :exclude [with-open])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.error :as err])
  (:import [clojure.lang Keyword MapEntry Symbol]
           (java.io ByteArrayOutputStream File)
           java.lang.AutoCloseable
           java.lang.reflect.Method
           (java.net MalformedURLException URI URL)
           java.nio.ByteBuffer
           (java.nio.channels Channels ClosedByInterruptException FileChannel FileChannel$MapMode SeekableByteChannel)
           java.nio.charset.StandardCharsets
           (java.nio.file CopyOption FileVisitResult Files LinkOption OpenOption Path Paths SimpleFileVisitor StandardCopyOption StandardOpenOption)
           java.nio.file.attribute.FileAttribute
           (java.util Collections LinkedHashMap Map UUID WeakHashMap)
           (java.util.concurrent CompletableFuture ExecutionException ExecutorService Executors ThreadFactory TimeUnit)
           (java.util.function Function)
           (org.apache.arrow.compression CommonsCompressionFactory)
           (org.apache.arrow.flatbuf Footer Message RecordBatch)
           (org.apache.arrow.memory AllocationManager ArrowBuf BufferAllocator)
           (org.apache.arrow.memory.util ByteFunctionHelpers MemoryUtil)
           (org.apache.arrow.vector ValueVector VectorLoader VectorSchemaRoot)
           (org.apache.arrow.vector.ipc ArrowFileWriter ArrowStreamWriter ArrowWriter)
           (org.apache.arrow.vector.ipc.message ArrowBlock ArrowFooter MessageSerializer)
           xtdb.util.NormalForm))

(set! *unchecked-math* :warn-on-boxed)

(defn maybe-update [m k f & args]
  (if (contains? m k)
    (apply update m k f args)
    m))

(defn close [c]
  (cond
    (nil? c) nil
    (instance? AutoCloseable c) (.close ^AutoCloseable c)
    (instance? Map c) (run! close (.values ^Map c))
    (seqable? c) (run! close c)
    :else (throw (ClassCastException. (format "could not close '%s'" (.getName (class c)))))))

(defn try-close [c]
  (cond
    (nil? c) nil
    (instance? AutoCloseable c) (try
                                  (.close ^AutoCloseable c)
                                  (catch Throwable e
                                    (log/warn e "could not close")))
    (instance? Map c) (recur (.values ^Map c))
    (seqable? c) (run! try-close c)
    :else (throw (ClassCastException. (format "could not close '%s'" (.getName (class c)))))))

(defmacro rethrowing-cause [form]
  `(try
     ~form
     (catch ExecutionException e#
       (throw (.getCause e#)))))

(defmacro with-open
  "Like `clojure.core/with-open` except closes sequences of things, and stores close exceptions
  as suppressed exceptions on the original exception so as not to mask the original exception"
  [bindings & body]
  (assert (zero? ^long (mod (count bindings) 2)))

  (if-let [[binding expr & more-bindings] bindings]
    `(let [obj# ~expr
           ~binding obj#]
       (let [res# (try
                   (with-open ~more-bindings ~@body)
                   (catch Throwable t#
                     (try
                       (close obj#)
                       (catch Throwable s#
                         (.addSuppressed t# s#)))
                     (throw t#)))]
         (close obj#)
         res#))

    `(do ~@body)))

(defmacro with-close-on-catch
  "Like `with-open` but doesn't close the resources if the body completes successfully.

  Used where you're opening multiple resources and want to ensure earlier ones are closed if initialising later ones fails."
  [bindings & body]
  (assert (zero? ^long (mod (count bindings) 2)))

  (if-let [[binding expr & more-bindings] bindings]
    `(let [~binding ~expr]
       (try
         (with-close-on-catch ~more-bindings ~@body)
         (catch Throwable t#
           (try
             (close ~binding)
             (catch Throwable s#
               (.addSuppressed t# s#)))
           (throw t#))))

    `(do ~@body)))

(defn uuid->bytes ^bytes [^UUID uuid]
  (let [bb (doto (ByteBuffer/allocate 16)
             (.putLong (.getMostSignificantBits uuid))
             (.putLong (.getLeastSignificantBits uuid)))]
    (.array bb)))

(defn uuid->byte-buffer ^ByteBuffer [^UUID uuid]
  (let [bb (doto (ByteBuffer/allocate 16)
             (.putLong (.getMostSignificantBits uuid))
             (.putLong (.getLeastSignificantBits uuid)))]
    (.position bb 0)
    bb))

(defn byte-buffer->uuid [^ByteBuffer bb]
  (UUID. (.getLong bb 0) (.getLong bb Long/BYTES)))

(defn ->lex-hex-string
  "Turn a long into a lexicographically-sortable hex string by prepending the length"
  [^long l]

  (let [s (format "%x" l)]
    (format "%x%s" (dec (count s)) s)))

(defn <-lex-hex-string [^String s]
  (Long/parseLong (subs s 1) 16))

;;; Common specs

(defn ->path ^Path [path-ish]
  (cond
    (instance? Path path-ish) path-ish
    (instance? File path-ish) (.toPath ^File path-ish)
    (uri? path-ish) (Paths/get ^URI path-ish)
    (string? path-ish) (let [uri (URI. path-ish)]
                         (if (.getScheme uri)
                           (Paths/get uri)
                           (Paths/get path-ish (make-array String 0))))
    :else ::s/invalid))

(s/def ::path
  (s/and (s/conformer ->path) #(instance? Path %)))

(defn ->url [url-ish]
  (try
    (io/as-url url-ish)
    (catch MalformedURLException _
      (.toURL (.toUri (->path url-ish))))))

(s/def ::url
  (s/and (s/conformer ->url) #(instance? URL %)))

(s/def ::string-map (s/map-of string? string?))

(defn component [node k]
  (some-> (:system node) (ig/find-derived k) first val))

;;; IO

(defn ->seekable-byte-channel ^java.nio.channels.SeekableByteChannel [^ByteBuffer buffer]
  (let [buffer (.slice buffer)]
    (reify SeekableByteChannel
      (isOpen [_] true)

      (close [_])

      (^int read [_ ^ByteBuffer dst]
        (let [^ByteBuffer src (-> buffer (.slice) (.limit (.remaining dst)))]
          (.put dst src)
          (let [bytes-read (.position src)]
            (.position buffer (+ (.position buffer) bytes-read))
            bytes-read)))

      (position [_] (.position buffer))

      (^SeekableByteChannel position [this ^long new-position]
        (.position buffer new-position)
        this)

      (size [_] (.capacity buffer))
      (write [_ _src] (throw (UnsupportedOperationException.)))
      (truncate [_ _size] (throw (UnsupportedOperationException.))))))

(defn enum->kw [^Enum enum-value]
  (-> (.name enum-value)
      (str/lower-case)
      (str/replace #"_" "-")
      keyword))

(def standard-open-options
  (->> (StandardOpenOption/values)
       (into {} (map (juxt enum->kw identity)))))

(def write-truncate-open-opts #{:create :write :truncate-existing})

(defn ->file-channel
  (^java.nio.channels.FileChannel [^Path path]
   (->file-channel path #{:read}))
  (^java.nio.channels.FileChannel [^Path path open-opts]
   (FileChannel/open path (into-array OpenOption (map #(standard-open-options % %) open-opts)))))

(defn ->mmap-path
  (^java.nio.MappedByteBuffer [^Path path]
   (->mmap-path path FileChannel$MapMode/READ_ONLY))
  (^java.nio.MappedByteBuffer [^Path path ^FileChannel$MapMode map-mode]
   (with-open [in (->file-channel path (if (= FileChannel$MapMode/READ_ONLY map-mode)
                                         #{:read}
                                         #{:read :write}))]
     (.map in map-mode 0 (.size in))))
  (^java.nio.MappedByteBuffer [^Path path ^FileChannel$MapMode map-mode ^long start ^long len]
   (with-open [in (->file-channel path (if (= FileChannel$MapMode/READ_ONLY map-mode)
                                         #{:read}
                                         #{:read :write}))]
     (.map in map-mode start len))))

(def ^:private file-deletion-visitor
  (proxy [SimpleFileVisitor] []
    (visitFile [file _]
      (Files/delete file)
      FileVisitResult/CONTINUE)

    (postVisitDirectory [dir _]
      (Files/delete dir)
      FileVisitResult/CONTINUE)))

(defn path-exists [^Path path]
  (Files/exists path (make-array LinkOption 0)))

(defn delete-dir [^Path dir]
  (when (path-exists dir)
    (Files/walkFileTree dir file-deletion-visitor)))

(defn delete-file [^Path file]
  (Files/deleteIfExists file))

(defn mkdirs [^Path path]
  (Files/createDirectories path (make-array FileAttribute 0)))

(defn create-parents [^Path path]
  (let [parents (.getParent path)]
    (when-not (path-exists parents)
      (mkdirs parents))))

(defn file-extension [^File f]
  (second (re-find #"\.(.+?)$" (.getName f))))

(defn ->temp-file ^Path [^String prefix ^String suffix]
  (doto (Files/createTempFile prefix suffix (make-array FileAttribute 0))
    (delete-file)))

(defn write-buffer-to-path [^ByteBuffer from-buffer ^Path to-path]
  (with-open [file-ch (->file-channel to-path write-truncate-open-opts)
              buf-ch (->seekable-byte-channel from-buffer)]
    (.transferFrom file-ch buf-ch 0 (.size buf-ch))))

(defn atomic-move [^Path from-path ^Path to-path]
  (Files/move from-path to-path (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
  to-path)

(defn prefix-key [^Path prefix ^Path k]
  (cond->> k
    prefix (.resolve prefix)))

(def ^java.nio.file.Path tables-dir (->path "tables"))

(defn table-name->table-path [^String table-name]
  (.resolve tables-dir table-name))

(defn ->jfn {:style/indent :defn} ^java.util.function.Function [f]
  (reify Function
    (apply [_ v]
      (f v))))

(defn then-apply {:style/indent :defn}
  ^java.util.concurrent.CompletableFuture
  [^CompletableFuture fut f]
  (.thenApply fut (->jfn f)))

(defn then-compose {:style/indent :defn}
  ^java.util.concurrent.CompletableFuture
  [^CompletableFuture fut f]
  (.thenCompose fut (->jfn f)))

(def uncaught-exception-handler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ _thread throwable]
      (log/error throwable "Uncaught exception:"))))

(defn install-uncaught-exception-handler! []
  (when-not (Thread/getDefaultUncaughtExceptionHandler)
    (Thread/setDefaultUncaughtExceptionHandler uncaught-exception-handler)))

(defn ->prefix-thread-factory ^java.util.concurrent.ThreadFactory [^String prefix]
  (let [default-thread-factory (Executors/defaultThreadFactory)]
    (reify ThreadFactory
      (newThread [_ r]
        (let [t (.newThread default-thread-factory r)]
          (doto t
            (.setName (str prefix "-" (.getName t)))
            (.setUncaughtExceptionHandler uncaught-exception-handler)))))))

(defn shutdown-pool
  ([^ExecutorService pool]
   (shutdown-pool pool 60))
  ([^ExecutorService pool ^long timeout-seconds]
   (.shutdownNow pool)
   (when-not (.awaitTermination pool timeout-seconds TimeUnit/SECONDS)
     (log/warn "pool did not terminate" pool))))

;;; Arrow

(defn slice-vec
  ([^ValueVector v] (slice-vec v 0))
  ([^ValueVector v, ^long start-idx] (slice-vec v start-idx (.getValueCount v)))

  ([^ValueVector v, ^long start-idx, ^long len]
   (-> (.getTransferPair v (.getAllocator v))
       (doto (.splitAndTransfer start-idx len))
       (.getTo))))

(defn build-arrow-ipc-byte-buffer ^java.nio.ByteBuffer {:style/indent 2}
  [^VectorSchemaRoot root ipc-type f]

  (try
    (with-open [baos (ByteArrayOutputStream.)
                ch (Channels/newChannel baos)
                ^ArrowWriter sw (case ipc-type
                                  :file (ArrowFileWriter. root nil ch)
                                  :stream (ArrowStreamWriter. root nil ch))]

      (.start sw)

      (f (fn write-batch! []
           (.writeBatch sw)))

      (.end sw)

      (ByteBuffer/wrap (.toByteArray baos)))
    (catch ClosedByInterruptException e
      (throw (InterruptedException. (.getMessage e))))))

(defn root->arrow-ipc-byte-buffer ^java.nio.ByteBuffer [^VectorSchemaRoot root ipc-type]
  (build-arrow-ipc-byte-buffer root ipc-type
    (fn [write-batch!]
      (write-batch!))))

(def ^{:tag 'bytes} arrow-magic (.getBytes "ARROW1" StandardCharsets/UTF_8))

(defn- validate-arrow-magic [^ArrowBuf ipc-file-format-buffer]
  (when-not (zero? (ByteFunctionHelpers/compare ipc-file-format-buffer
                                                (- (.capacity ipc-file-format-buffer) (alength arrow-magic))
                                                (.capacity ipc-file-format-buffer)
                                                arrow-magic
                                                0
                                                (alength arrow-magic)))
    (throw (err/illegal-arg ::invalid-arrow-ipc-file
                            {::err/message "invalid Arrow IPC file format"}))))

(defn read-arrow-footer ^org.apache.arrow.vector.ipc.message.ArrowFooter [^ArrowBuf ipc-file-format-buffer]
  (validate-arrow-magic ipc-file-format-buffer)
  (let [footer-size-offset (- (.capacity ipc-file-format-buffer) (+ Integer/BYTES (alength arrow-magic)))
        footer-size (.getInt ipc-file-format-buffer footer-size-offset)
        footer-position (- footer-size-offset footer-size)
        footer-bb (.nioBuffer ipc-file-format-buffer footer-position footer-size)]
    (ArrowFooter. (Footer/getRootAsFooter footer-bb))))

(defn- try-open-reflective-access [^Class from ^Class to]
  (try
    (let [this-module (.getModule from)]
      (when-not (.isNamed this-module)
        (.addOpens (.getModule to) (.getName (.getPackage to)) this-module)))
    (catch Exception e
      (log/warn e "could not open reflective access from" from "to" to))))

#_{:clj-kondo/ignore [:unused-private-var]} ; side-effect
(defonce ^:private direct-byte-buffer-access
  (try-open-reflective-access ArrowBuf (class (ByteBuffer/allocateDirect 0))))

(def ^:private try-free-direct-buffer
  (try
    (Class/forName "io.netty.util.internal.PlatformDependent")
    (eval
     '(fn free-direct-buffer [nio-buffer]
        (try
          (io.netty.util.internal.PlatformDependent/freeDirectBuffer nio-buffer)
          (catch Exception e
            ;; NOTE: this happens when we give a sliced buffer from
            ;; the in-memory object store to the buffer pool.
            (let [cause (.getCause e)]
              (when-not (and (instance? IllegalArgumentException cause)
                             (= "duplicate or slice" (.getMessage cause)))
                (throw e)))))))
    (catch ClassNotFoundException _
      (fn free-direct-buffer-nop [_]))))

(def ^:private ^Method allocation-manager-associate-method
  (doto (.getDeclaredMethod AllocationManager "associate" (into-array Class [BufferAllocator]))
    (.setAccessible true)))

(defn ->arrow-buf-view
  (^org.apache.arrow.memory.ArrowBuf [^BufferAllocator allocator ^ByteBuffer nio-buffer]
   (->arrow-buf-view allocator nio-buffer nil))
  (^org.apache.arrow.memory.ArrowBuf [^BufferAllocator allocator ^ByteBuffer nio-buffer on-close-fn]
   (let [nio-buffer (if (and (.isDirect nio-buffer) (zero? (.position nio-buffer)))
                      nio-buffer
                      (-> (ByteBuffer/allocateDirect (.remaining nio-buffer))
                          (.put (.duplicate nio-buffer))
                          (.clear)))
         address (MemoryUtil/getByteBufferAddress nio-buffer)
         size (.remaining nio-buffer)
         allocation-manager (proxy [AllocationManager] [allocator]
                              (getSize [] size)
                              (memoryAddress [] address)
                              (release0 []
                                (try-free-direct-buffer nio-buffer)
                                (when on-close-fn
                                  (on-close-fn))))
         listener (.getListener allocator)
         _ (with-open [reservation (.newReservation allocator)]
             (.onPreAllocation listener size)
             (.reserve reservation size)
             (.onAllocation listener size))
         buffer-ledger (.invoke allocation-manager-associate-method allocation-manager (object-array [allocator]))]
     (ArrowBuf. buffer-ledger nil size address))))

(defn ->arrow-record-batch-view ^org.apache.arrow.vector.ipc.message.ArrowRecordBatch [^ArrowBlock block ^ArrowBuf buffer]
  (let [prefix-size (if (= (.getInt buffer (.getOffset block)) MessageSerializer/IPC_CONTINUATION_TOKEN)
                      8
                      4)
        ^RecordBatch batch (.header (Message/getRootAsMessage
                                     (.nioBuffer buffer
                                                 (+ (.getOffset block) prefix-size)
                                                 (- (.getMetadataLength block) prefix-size)))
                                    (RecordBatch.))
        body-buffer (doto (.slice buffer
                                  (+ (.getOffset block)
                                     (.getMetadataLength block))
                                  (.getBodyLength block))
                      (-> (.getReferenceManager) (.retain)))]
    (MessageSerializer/deserializeRecordBatch batch body-buffer)))

(defn read-arrow-buf [^ArrowBuf ipc-file-format-buffer]
  (let [footer (read-arrow-footer ipc-file-format-buffer)
        root (VectorSchemaRoot/create (.getSchema footer) (.getAllocator (.getReferenceManager ipc-file-format-buffer)))]
    {:arrow-blocks (.getRecordBatches footer)
     :root root
     :loader (VectorLoader. root CommonsCompressionFactory/INSTANCE)}))

(defn compare-nio-buffers-unsigned ^long [^ByteBuffer x ^ByteBuffer y]
  (let [rem-x (.remaining x)
        rem-y (.remaining y)
        limit (min rem-x rem-y)
        char-limit (bit-shift-right limit 1)
        diff (.compareTo (.limit (.asCharBuffer x) char-limit)
                         (.limit (.asCharBuffer y) char-limit))]
    (if (zero? diff)
      (loop [n (bit-and-not limit 1)]
        (if (= n limit)
          (- rem-x rem-y)
          (let [x-byte (.get x n)
                y-byte (.get y n)]
            (if (= x-byte y-byte)
              (recur (inc n))
              (Byte/compareUnsigned x-byte y-byte)))))
      diff)))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals.

  See: https://stackoverflow.com/questions/16777814/is-it-possible-to-use-clojures-case-form-with-a-java-enum"
  {:style/indent 1}
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
           (mapcat (fn [[test result]]
                     [(eval (enum-ordinal test)) result])
                   (partition 2 clauses))
           (when (odd? (count clauses))
             (list (last clauses)))))))

(def ^:const default-lru-memoize-size (* 4 1024))

(def ^:private ^Map known-memo-tables (WeakHashMap.))

(defn lru-memoize
  ([f]
   (lru-memoize default-lru-memoize-size f))
  ([^long cache-size f]
   (let [cache (Collections/synchronizedMap (proxy [LinkedHashMap] [cache-size 0.75 true]
                                              (removeEldestEntry [_]
                                                (> (.size ^Map this) cache-size))))]
     (.put known-memo-tables f cache)
     (fn
       ([]
        (let [k f
              v (.getOrDefault cache k ::not-found)]
          (if (identical? ::not-found v)
            (doto (f)
              (->> (.put cache k)))
            v)))
       ([x]
        (let [k x
              v (.getOrDefault cache k ::not-found)]
          (if (identical? ::not-found v)
            (doto (f x)
              (->> (.put cache k)))
            v)))
       ([x y]
        (let [k (MapEntry/create x y)
              v (.getOrDefault cache k ::not-found)]
          (if (identical? ::not-found v)
            (doto (f x y)
              (->> (.put cache k)))
            v)))
       ([x y & args]
        (let [k (cons x (cons y args))
              v (.getOrDefault cache k ::not-found)]
          (if (identical? ::not-found v)
            (doto (apply f k)
              (->> (.put cache k)))
            v)))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn clear-known-lru-memo-tables! []
  (doseq [[_ ^Map table] known-memo-tables]
    (.clear table)))

(defn ->kebab-case-kw [s]
  (-> s name str/lower-case (str/replace "_" "-") keyword))

(defn kw->normal-form-kw [^Keyword kw]
  (NormalForm/normalForm kw))

(defn symbol->normal-form-symbol ^Symbol [^Symbol s]
  (NormalForm/normalForm s))

(defn str->normal-form-str ^String [^String s]
  (NormalForm/normalForm s))

(defn ->child-allocator [^BufferAllocator allocator name]
  (.newChildAllocator allocator name (.getInitReservation allocator) (.getLimit allocator)))

(defn with-tmp-dir* [prefix f]
  (let [dir (Files/createTempDirectory prefix (make-array FileAttribute 0))]
    (try
      (f dir)
      (finally
        (delete-dir dir)))))

(defn tmp-dir
  (^Path [] (tmp-dir ""))
  (^Path [prefix] (Files/createTempDirectory prefix (make-array FileAttribute 0)) ))

(defmacro with-tmp-dirs
  "Usage:
    (with-tmp-dirs #{log-dir objects-dir}
      ...)"
  [[dir-binding & more-bindings] & body]
  (if dir-binding
    `(with-tmp-dir* ~(name dir-binding)
       (fn [~(vary-meta dir-binding assoc :tag 'java.nio.file.Path)]
         (with-tmp-dirs #{~@more-bindings}
           ~@body)))
    `(do ~@body)))
