(ns xtdb.node
  (:require [clojure.pprint :as pp]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api.protocols :as xtp]
            [xtdb.datalog :as d]
            xtdb.indexer
            [xtdb.operator :as op]
            [xtdb.operator.scan :as scan]
            [xtdb.sql :as sql]
            [xtdb.tx-producer :as txp]
            [xtdb.util :as util]
            [xtdb.xtql :as xtql]
            [xtdb.xtql.edn :as xtql.edn]
            [xtdb.logical-plan :as lp])
  (:import (java.io Closeable Writer)
           (java.lang AutoCloseable)
           (java.time ZoneId)
           (java.util.concurrent CompletableFuture)
           (org.apache.arrow.memory BufferAllocator RootAllocator)
           xtdb.indexer.IIndexer
           xtdb.operator.IRaQuerySource
           xtdb.tx.Ops$Sql
           (xtdb.tx_producer ITxProducer)))

(set! *unchecked-math* :warn-on-boxed)

(defmethod ig/init-key :xtdb/allocator [_ _] (RootAllocator.))
(defmethod ig/halt-key! :xtdb/allocator [_ ^BufferAllocator a]
  (util/close a))

(defmethod ig/prep-key :xtdb/default-tz [_ default-tz]
  (cond
    (instance? ZoneId default-tz) default-tz
    (string? default-tz) (ZoneId/of default-tz)
    :else util/utc))

(defmethod ig/init-key :xtdb/default-tz [_ default-tz] default-tz)

(defn- validate-tx-ops [tx-ops]
  (try
    (doseq [tx-op (->> tx-ops
                       (map (fn [tx-op]
                              (cond-> tx-op
                                (vector? tx-op) txp/parse-tx-op))))
            :when (instance? Ops$Sql tx-op)]
      (sql/parse-query (.sql ^Ops$Sql tx-op)))
    (catch Throwable e
      (CompletableFuture/failedFuture e))))

(defn- with-after-tx-default [opts]
  (-> opts
      (update-in [:basis :after-tx] xtp/max-tx (get-in opts [:basis :tx]))))

(defrecord Node [^BufferAllocator allocator
                 ^IIndexer indexer
                 ^ITxProducer tx-producer
                 ^IRaQuerySource ra-src, wm-src, scan-emitter
                 default-tz
                 !latest-submitted-tx
                 system, close-fn]
  xtp/PNode
  (open-query& [_ query query-opts]
    (let [query-opts (-> (into {:default-tz default-tz} query-opts)
                         (with-after-tx-default))
          !await-tx (.awaitTxAsync indexer (get-in query-opts [:basis :after-tx]) (:basis-timeout query-opts))]

      (cond
        (string? query) (-> !await-tx
                            (util/then-apply
                              (fn [_]
                                (let [tables-with-cols (scan/tables-with-cols (:basis query-opts) wm-src scan-emitter)
                                      ra (sql/compile-query query (assoc query-opts :table-info tables-with-cols))]
                                  (if (:explain? query-opts)
                                    (lp/explain-result ra)
                                    (let [pq (.prepareRaQuery ra-src ra)]
                                      (sql/open-sql-query allocator wm-src pq query-opts)))))))

        (map? query) (-> !await-tx
                         (util/then-apply
                           (fn [_]
                             (d/open-datalog-query allocator ra-src wm-src scan-emitter query query-opts))))

        (list? query) (-> !await-tx
                          (util/then-apply
                            (fn [_]
                              (let [query (xtql.edn/parse-query query)
                                    parsed-query-opts (xtql.edn/parse-query-opts query-opts)]
                                (xtql/open-xtql-query allocator ra-src wm-src scan-emitter
                                                      query parsed-query-opts query-opts))))))))

  (latest-submitted-tx [_] @!latest-submitted-tx)

  xtp/PStatus
  (status [this]
    {:latest-completed-tx (.latestCompletedTx indexer)
     :latest-submitted-tx (xtp/latest-submitted-tx this)})

  xtp/PSubmitNode
  (submit-tx& [this tx-ops]
    (xtp/submit-tx& this tx-ops {}))

  (submit-tx& [_ tx-ops opts]
    (-> (or (validate-tx-ops tx-ops)
            (.submitTx tx-producer tx-ops opts))
        (util/then-apply
          (fn [tx]
            (swap! !latest-submitted-tx xtp/max-tx tx)))))

  Closeable
  (close [_]
    (when close-fn
      (close-fn))))

(defmethod print-method Node [_node ^Writer w] (.write w "#<XtdbNode>"))
(defmethod pp/simple-dispatch Node [it] (print-method it *out*))

(defmethod ig/prep-key :xtdb/node [_ opts]
  (merge {:allocator (ig/ref :xtdb/allocator)
          :indexer (ig/ref :xtdb/indexer)
          :wm-src (ig/ref :xtdb/indexer)
          :tx-producer (ig/ref ::txp/tx-producer)
          :default-tz (ig/ref :xtdb/default-tz)
          :ra-src (ig/ref :xtdb.operator/ra-query-source)
          :scan-emitter (ig/ref :xtdb.operator.scan/scan-emitter)}
         opts))

(defmethod ig/init-key :xtdb/node [_ deps]
  (map->Node (-> deps
                 (assoc :!latest-submitted-tx (atom nil)))))

(defmethod ig/halt-key! :xtdb/node [_ node]
  (util/try-close node))

(defn- with-default-impl [opts parent-k impl-k]
  (cond-> opts
    (not (ig/find-derived opts parent-k)) (assoc impl-k {})))

(defn node-system [opts]
  (-> (into {:xtdb/node {}
             :xtdb/allocator {}
             :xtdb/default-tz nil
             :xtdb/indexer {}
             :xtdb.indexer/live-index {}
             :xtdb.log/watcher {}
             :xtdb.metadata/metadata-manager {}
             :xtdb.operator.scan/scan-emitter {}
             :xtdb.operator/ra-query-source {}
             ::txp/tx-producer {}
             :xtdb.stagnant-log-flusher/flusher {}
             :xtdb/compactor {}}
            opts)
      (doto ig/load-namespaces)
      (with-default-impl :xtdb/log :xtdb.log/memory-log)
      (with-default-impl :xtdb/buffer-pool :xtdb.buffer-pool/in-memory)
      (doto ig/load-namespaces)))

(defn start-node ^xtdb.node.Node [opts]
  (let [system (-> (node-system opts)
                   ig/prep
                   ig/init)]

    (-> (:xtdb/node system)
        (assoc :system system
               :close-fn #(do (ig/halt! system)
                              #_(println (.toVerboseString ^RootAllocator (:xtdb/allocator system))))))))

(defrecord SubmitNode [^ITxProducer tx-producer, !system, close-fn]
  xtp/PSubmitNode
  (submit-tx& [this tx-ops]
    (xtp/submit-tx& this tx-ops {}))

  (submit-tx& [_ tx-ops opts]
    (or (validate-tx-ops tx-ops)
        (.submitTx tx-producer tx-ops opts)))

  AutoCloseable
  (close [_]
    (when close-fn
      (close-fn))))

(defmethod print-method SubmitNode [_node ^Writer w] (.write w "#<XtdbSubmitNode>"))
(defmethod pp/simple-dispatch SubmitNode [it] (print-method it *out*))

(defmethod ig/prep-key ::submit-node [_ opts]
  (merge {:tx-producer (ig/ref :xtdb.tx-producer/tx-producer)}
         opts))

(defmethod ig/init-key ::submit-node [_ {:keys [tx-producer]}]
  (map->SubmitNode {:tx-producer tx-producer, :!system (atom nil)}))

(defmethod ig/halt-key! ::submit-node [_ ^SubmitNode node]
  (.close node))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn start-submit-node ^xtdb.node.SubmitNode [opts]
  (let [system (-> (into {::submit-node {}
                          :xtdb.tx-producer/tx-producer {}
                          :xtdb/allocator {}
                          :xtdb/default-tz nil}
                         opts)
                   (doto ig/load-namespaces)
                   (with-default-impl :xtdb/log :xtdb.log/memory-log)
                   (doto ig/load-namespaces)
                   ig/prep
                   ig/init)]

    (-> (::submit-node system)
        (doto (-> :!system (reset! system)))
        (assoc :close-fn #(ig/halt! system)))))
