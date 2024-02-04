(ns xtdb.log
  (:require [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.error :as err]
            [xtdb.node :as xtn]
            xtdb.protocols
            [xtdb.sql :as sql]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import java.lang.AutoCloseable
           (java.nio.channels ClosedChannelException)
           (java.time Instant)
           java.time.Duration
           (java.util ArrayList HashMap)
           (java.util.concurrent CompletableFuture Semaphore)
           org.apache.arrow.memory.BufferAllocator
           (org.apache.arrow.vector VectorSchemaRoot)
           (org.apache.arrow.vector.types.pojo ArrowType$Union FieldType Schema)
           org.apache.arrow.vector.types.UnionMode
           (xtdb.api.log Log Log$Factory Log$Record Log$Subscriber)
           (xtdb.api.tx TxOp$Abort TxOp$Call TxOp$DeleteDocs TxOp$EraseDocs TxOp$PutDocs TxOp$Sql TxOp$SqlByteArgs TxOp$XtqlAndArgs TxOp$XtqlOp TxOptions)
           xtdb.types.ClojureForm
           xtdb.vector.IVectorWriter))

(set! *unchecked-math* :warn-on-boxed)

(def ^java.util.concurrent.ThreadFactory subscription-thread-factory
  (util/->prefix-thread-factory "xtdb-tx-subscription"))

(defn- tx-handler [^Log$Subscriber subscriber]
  (fn [_last-tx-id ^Log$Record record]
    (when (Thread/interrupted)
      (throw (InterruptedException.)))

    (.acceptRecord subscriber record)

    (.getTxId (.getTxKey record))))

(defn handle-polling-subscription [^Log log, after-tx-id, {:keys [^Duration poll-sleep-duration]}, ^Log$Subscriber subscriber]
  (doto (.newThread subscription-thread-factory
                    (fn []
                      (let [thread (Thread/currentThread)]
                        (.onSubscribe subscriber (reify AutoCloseable
                                                   (close [_]
                                                     (.interrupt thread)
                                                     (.join thread)))))
                      (try
                        (loop [after-tx-id after-tx-id]
                          (let [last-tx-id (reduce (tx-handler subscriber)
                                                   after-tx-id
                                                   (try
                                                     (.readRecords log after-tx-id 100)
                                                     (catch ClosedChannelException e (throw e))
                                                     (catch InterruptedException e (throw e))
                                                     (catch Exception e
                                                       (log/warn e "Error polling for txs, will retry"))))]
                            (when (Thread/interrupted)
                              (throw (InterruptedException.)))
                            (when (= after-tx-id last-tx-id)
                              (Thread/sleep (.toMillis poll-sleep-duration)))
                            (recur last-tx-id)))
                        (catch InterruptedException _)
                        (catch ClosedChannelException _))))
    (.start)))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface INotifyingSubscriberHandler
  (notifyTx [^xtdb.api.TransactionKey tx])
  (subscribe [^xtdb.api.log.Log log, ^Long after-tx-id, ^xtdb.api.log.Log$Subscriber subscriber]))

(defrecord NotifyingSubscriberHandler [!state]
  INotifyingSubscriberHandler
  (notifyTx [_ tx]
    (let [{:keys [semaphores]} (swap! !state assoc :latest-submitted-tx-id (.getTxId tx))]
      (doseq [^Semaphore semaphore semaphores]
        (.release semaphore))))

  (subscribe [_ log after-tx-id subscriber]
    (let [semaphore (Semaphore. 0)
          {:keys [latest-submitted-tx-id]} (swap! !state update :semaphores conj semaphore)]

      (doto (.newThread subscription-thread-factory
                        (fn []
                          (let [thread (Thread/currentThread)]
                            (.onSubscribe subscriber (reify AutoCloseable
                                                       (close [_]
                                                         (.interrupt thread)
                                                         (.join thread)))))
                          (try
                            (loop [after-tx-id after-tx-id]
                              (let [last-tx-id (reduce (tx-handler subscriber)
                                                       after-tx-id
                                                       (if (and latest-submitted-tx-id
                                                                (or (nil? after-tx-id)
                                                                    (< ^long after-tx-id ^long latest-submitted-tx-id)))
                                                         ;; catching up
                                                         (->> (.readRecords log after-tx-id 100)
                                                              (take-while #(<= ^long (.getTxId (.getTxKey ^Log$Record %))
                                                                               ^long latest-submitted-tx-id)))

                                                         ;; running live
                                                         (let [permits (do
                                                                         (.acquire semaphore)
                                                                         (inc (.drainPermits semaphore)))]
                                                           (.readRecords log after-tx-id
                                                                         (if (> permits 100)
                                                                           (do
                                                                             (.release semaphore (- permits 100))
                                                                             100)
                                                                           permits)))))]
                                (when-not (Thread/interrupted)
                                  (recur last-tx-id))))

                            (catch InterruptedException _)

                            (catch ClosedChannelException ex
                              (when-not (Thread/interrupted)
                                (throw ex)))

                            (finally
                              (swap! !state update :semaphores disj semaphore)))))
        (.start)))))

(defn ->notifying-subscriber-handler [latest-submitted-tx-id]
  (->NotifyingSubscriberHandler (atom {:latest-submitted-tx-id latest-submitted-tx-id
                                       :semaphores #{}})))

;; header bytes
(def ^:const hb-user-arrow-transaction
  "Header byte for log records representing an arrow user transaction.

  A standard arrow stream IPC buffer will contain this byte, so you do not need to prefix."
  255)

(def ^:const hb-flush-chunk
  "Header byte for log records representing a signal to flush the live chunk to durable storage.

  Can be useful to protect against data loss potential when a retention period is used for the log, so messages do not remain in the log forever.

  Record layout:

  - header (byte=2)

  - expected-last-tx-id in previous chunk (long)
  If this tx-id match the last tx-id who has been indexed in durable storage, then this signal is ignored.
  This is to avoid a herd effect in multi-node environments where multiple flush signals for the same chunk might be received.

  See xtdb.stagnant-log-flusher"
  2)

(def ^:private ^org.apache.arrow.vector.types.pojo.Field tx-ops-field
  (types/->field "tx-ops" (ArrowType$Union. UnionMode/Dense nil) false))

(def ^:private ^org.apache.arrow.vector.types.pojo.Schema tx-schema
  (Schema. [(types/->field "tx-ops" #xt.arrow/type :list false tx-ops-field)

            (types/col-type->field "system-time" types/nullable-temporal-type)
            (types/col-type->field "default-tz" :utf8)
            (types/col-type->field "default-all-valid-time?" :bool)]))

(defn- ->xtql+args-writer [^IVectorWriter op-writer, ^BufferAllocator allocator]
  (let [xtql-writer (.legWriter op-writer :xtql (FieldType/notNullable #xt.arrow/type :struct))
        xtql-op-writer (.structKeyWriter xtql-writer "op" (FieldType/notNullable #xt.arrow/type :transit))
        args-writer (.structKeyWriter xtql-writer "args" (FieldType/nullable #xt.arrow/type :varbinary))]
    (fn write-xtql+args! [^TxOp$XtqlAndArgs op+args]
      (.startStruct xtql-writer)
      (vw/write-value! (ClojureForm. (.op op+args)) xtql-op-writer)

      (when-let [arg-rows (.argRows op+args)]
        (util/with-open [args-wtr (vw/->vec-writer allocator "args" (FieldType/notNullable #xt.arrow/type :struct))]
          (doseq [arg-row arg-rows]
            (vw/write-value! arg-row args-wtr))

          (.syncValueCount args-wtr)

          (.writeBytes args-writer
                       (util/build-arrow-ipc-byte-buffer (VectorSchemaRoot. ^Iterable (seq (.getVector args-wtr)))
                                                         :stream
                                                         (fn [write-batch!]
                                                           (write-batch!))))))

      (.endStruct xtql-writer))))

(defn- ->xtql-writer [^IVectorWriter op-writer]
  (let [xtql-writer (.legWriter op-writer :xtql (FieldType/notNullable #xt.arrow/type :struct))
        xtql-op-writer (.structKeyWriter xtql-writer "op" (FieldType/notNullable #xt.arrow/type :transit))]

    ;; create this even if it's not required here
    (.structKeyWriter xtql-writer "args" (FieldType/nullable #xt.arrow/type :varbinary))

    (fn write-xtql! [^TxOp$XtqlOp op]
      (.startStruct xtql-writer)
      (vw/write-value! (ClojureForm. op) xtql-op-writer)
      (.endStruct xtql-writer))))

(defn encode-sql-args [^BufferAllocator allocator, query, arg-rows]
  (let [plan (sql/compile-query query)
        {:keys [^long param-count]} (meta plan)

        vecs (ArrayList. param-count)]
    (try
      ;; TODO check arg count in each row, handle error
      (dotimes [col-idx param-count]
        (.add vecs
              (vw/open-vec allocator (symbol (str "?_" col-idx))
                           (mapv #(nth % col-idx) arg-rows))))

      (let [root (doto (VectorSchemaRoot. vecs) (.setRowCount (count arg-rows)))]
        (util/build-arrow-ipc-byte-buffer root :stream
          (fn [write-batch!]
            (write-batch!))))

      (finally
        (run! util/try-close vecs)))))

(defn- ->sql-writer [^IVectorWriter op-writer, ^BufferAllocator allocator]
  (let [sql-writer (.legWriter op-writer :sql (FieldType/notNullable #xt.arrow/type :struct))
        query-writer (.structKeyWriter sql-writer "query" (FieldType/notNullable #xt.arrow/type :utf8))
        args-writer (.structKeyWriter sql-writer "args" (FieldType/nullable #xt.arrow/type :varbinary))]
    (fn write-sql! [^TxOp$Sql op]
      (let [sql (.sql op)]
        (.startStruct sql-writer)
        (vw/write-value! sql query-writer)

        (when-let [arg-rows (.argRows op)]
          (vw/write-value! (encode-sql-args allocator sql arg-rows) args-writer)))

      (.endStruct sql-writer))))

(defn- ->sql-byte-args-writer [^IVectorWriter op-writer]
  (let [sql-writer (.legWriter op-writer :sql (FieldType/notNullable #xt.arrow/type :struct))
        query-writer (.structKeyWriter sql-writer "query" (FieldType/notNullable #xt.arrow/type :utf8))
        args-writer (.structKeyWriter sql-writer "args" (FieldType/nullable #xt.arrow/type :varbinary))]
    (fn write-sql! [^TxOp$SqlByteArgs op]
      (let [sql (.sql op)]
        (.startStruct sql-writer)
        (vw/write-value! sql query-writer)

        (when-let [arg-bytes (.argBytes op)]
          (vw/write-value! arg-bytes args-writer)))

      (.endStruct sql-writer))))

(defn- ->put-writer [^IVectorWriter op-writer]
  (let [put-writer (.legWriter op-writer :put-docs (FieldType/notNullable #xt.arrow/type :struct))
        doc-writer (.structKeyWriter put-writer "documents" (FieldType/notNullable #xt.arrow/type :union))
        valid-from-writer (.structKeyWriter put-writer "xt$valid_from" types/nullable-temporal-field-type)
        valid-to-writer (.structKeyWriter put-writer "xt$valid_to" types/nullable-temporal-field-type)
        table-doc-writers (HashMap.)]
    (fn write-put! [^TxOp$PutDocs op]
      (.startStruct put-writer)
      (let [table-doc-writer (.computeIfAbsent table-doc-writers (util/str->normal-form-str (.tableName op))
                                               (util/->jfn
                                                 (fn [table]
                                                   (doto (.legWriter doc-writer (keyword table) (FieldType/notNullable #xt.arrow/type :list))
                                                     (.listElementWriter (FieldType/notNullable #xt.arrow/type :struct))))))]

        (vw/write-value! (.docs op) table-doc-writer))

      (vw/write-value! (.validFrom op) valid-from-writer)
      (vw/write-value! (.validTo op) valid-to-writer)

      (.endStruct put-writer))))

(defn- ->delete-writer [^IVectorWriter op-writer]
  (let [delete-writer (.legWriter op-writer :delete-docs (FieldType/notNullable #xt.arrow/type :struct))
        table-writer (.structKeyWriter delete-writer "table" (FieldType/notNullable #xt.arrow/type :utf8))
        doc-ids-writer (.structKeyWriter delete-writer "doc-ids" (FieldType/notNullable #xt.arrow/type :list))
        valid-from-writer (.structKeyWriter delete-writer "xt$valid_from" types/nullable-temporal-field-type)
        valid-to-writer (.structKeyWriter delete-writer "xt$valid_to" types/nullable-temporal-field-type)]
    (fn write-delete! [^TxOp$DeleteDocs op]
      (when-let [doc-ids (not-empty (.docIds op))]
        (.startStruct delete-writer)

        (vw/write-value! (util/str->normal-form-str (.tableName op)) table-writer)

        (vw/write-value! (vec doc-ids) doc-ids-writer)

        (vw/write-value! (.validFrom op) valid-from-writer)
        (vw/write-value! (.validTo op) valid-to-writer)

        (.endStruct delete-writer)))))

(defn- ->erase-writer [^IVectorWriter op-writer]
  (let [erase-writer (.legWriter op-writer :erase-docs (FieldType/notNullable #xt.arrow/type :struct))
        table-writer (.structKeyWriter erase-writer "table" (FieldType/notNullable #xt.arrow/type :utf8))
        doc-ids-writer (.structKeyWriter erase-writer "doc-ids" (FieldType/notNullable #xt.arrow/type :list))]
    (fn [^TxOp$EraseDocs op]
      (when-let [doc-ids (not-empty (.docIds op))]
        (.startStruct erase-writer)
        (vw/write-value! (util/str->normal-form-str (.tableName op)) table-writer)

        (vw/write-value! (vec doc-ids) doc-ids-writer)

        (.endStruct erase-writer)))))

(defn- ->call-writer [^IVectorWriter op-writer]
  (let [call-writer (.legWriter op-writer :call (FieldType/notNullable #xt.arrow/type :struct))
        fn-id-writer (.structKeyWriter call-writer "fn-id" (FieldType/notNullable #xt.arrow/type :union))
        args-list-writer (.structKeyWriter call-writer "args" (FieldType/notNullable #xt.arrow/type :transit))]
    (fn write-call! [^TxOp$Call op]
      (.startStruct call-writer)

      (let [fn-id (.fnId op)]
        (vw/write-value! fn-id (.legWriter fn-id-writer (vw/value->arrow-type fn-id))))

      (let [clj-form (xt/->ClojureForm (vec (.args op)))]
        (vw/write-value! clj-form (.legWriter args-list-writer (vw/value->arrow-type clj-form))))

      (.endStruct call-writer))))

(defn- ->abort-writer [^IVectorWriter op-writer]
  (let [abort-writer (.legWriter op-writer :abort (FieldType/nullable #xt.arrow/type :null))]
    (fn [^TxOp$Abort _op]
      (.writeNull abort-writer))))

(defn open-tx-ops-vec ^org.apache.arrow.vector.ValueVector [^BufferAllocator allocator]
  (.createVector tx-ops-field allocator))

(defn write-tx-ops! [^BufferAllocator allocator, ^IVectorWriter op-writer, tx-ops]
  (let [!write-xtql+args! (delay (->xtql+args-writer op-writer allocator))
        !write-xtql! (delay (->xtql-writer op-writer))
        !write-sql! (delay (->sql-writer op-writer allocator))
        !write-sql-byte-args! (delay (->sql-byte-args-writer op-writer))
        !write-put! (delay (->put-writer op-writer))
        !write-delete! (delay (->delete-writer op-writer))
        !write-erase! (delay (->erase-writer op-writer))
        !write-call! (delay (->call-writer op-writer))
        !write-abort! (delay (->abort-writer op-writer))]

    (doseq [tx-op tx-ops]
      (condp instance? tx-op
        TxOp$XtqlAndArgs (@!write-xtql+args! tx-op)
        TxOp$XtqlOp (@!write-xtql! tx-op)
        TxOp$Sql (@!write-sql! tx-op)
        TxOp$SqlByteArgs (@!write-sql-byte-args! tx-op)
        TxOp$PutDocs (@!write-put! tx-op)
        TxOp$DeleteDocs (@!write-delete! tx-op)
        TxOp$EraseDocs (@!write-erase! tx-op)
        TxOp$Call (@!write-call! tx-op)
        TxOp$Abort (@!write-abort! tx-op)
        (throw (err/illegal-arg :invalid-tx-op {:tx-op tx-op}))))))

(defn serialize-tx-ops ^java.nio.ByteBuffer [^BufferAllocator allocator tx-ops {:keys [^Instant system-time, default-tz, default-all-valid-time?]}]
  (with-open [root (VectorSchemaRoot/create tx-schema allocator)]
    (let [ops-list-writer (vw/->writer (.getVector root "tx-ops"))

          default-tz-writer (vw/->writer (.getVector root "default-tz"))
          app-time-behaviour-writer (vw/->writer (.getVector root "default-all-valid-time?"))]

      (when system-time
        (vw/write-value! system-time (vw/->writer (.getVector root "system-time"))))

      (vw/write-value! (str default-tz) default-tz-writer)
      (vw/write-value! (boolean default-all-valid-time?) app-time-behaviour-writer)

      (.startList ops-list-writer)
      (write-tx-ops! allocator
                     (.listElementWriter ops-list-writer)
                     tx-ops)
      (.endList ops-list-writer)

      (.setRowCount root 1)
      (.syncSchema root)

      (util/root->arrow-ipc-byte-buffer root :stream))))

(defmethod xtn/apply-config! :log [config _ foo]
  (let [[tag opts] foo]
    (xtn/apply-config! config
                       (case tag
                         :in-memory :xtdb.log/memory-log
                         :local :xtdb.log/local-directory-log
                         :kafka :xtdb.kafka/log
                         :azure-event-hub :xtdb.azure/event-hub-log)
                       opts)))

(defmethod ig/init-key :xtdb/log [_ ^Log$Factory factory]
  (.openLog factory))

(defmethod ig/halt-key! :xtdb/log [_ ^Log log]
  (util/close log))

(defn- validate-tx-ops [tx-ops]
  (try
    (doseq [tx-op tx-ops
            :when (instance? TxOp$Sql tx-op)]
      (sql/parse-query (.sql ^TxOp$Sql tx-op)))
    (catch Throwable e
      (CompletableFuture/failedFuture e))))

(defn submit-tx& ^java.util.concurrent.CompletableFuture
  [{:keys [^BufferAllocator allocator, ^Log log, default-tz]} tx-ops ^TxOptions opts]

  (or (validate-tx-ops tx-ops)
      (let [system-time (some-> opts .getSystemTime)]
        (.appendRecord log (serialize-tx-ops allocator tx-ops
                                             {:default-tz (or (some-> opts .getDefaultTz) default-tz)
                                              :system-time system-time
                                              :default-all-valid-time? (some-> opts .getDefaultAllValidTime)})))))
