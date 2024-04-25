(ns xtdb.node.impl
  (:require [clojure.pprint :as pp]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as api]
            xtdb.indexer
            [xtdb.log :as log]
            [xtdb.metrics :as metrics]
            [xtdb.operator.scan :as scan]
            [xtdb.protocols :as xtp]
            [xtdb.query :as q]
            [xtdb.sql :as sql]
            [xtdb.time :as time]
            [xtdb.util :as util]
            [xtdb.xtql :as xtql])
  (:import (io.micrometer.core.instrument Timer)
           (java.io Closeable Writer)
           java.util.HashMap
           (java.util.concurrent CompletableFuture)
           [java.util.stream Stream]
           (org.apache.arrow.memory BufferAllocator RootAllocator)
           (xtdb.api IXtdb TransactionKey Xtdb$Config)
           (xtdb.api.log Log)
           xtdb.api.module.XtdbModule$Factory
           (xtdb.api.query Basis IKeyFn QueryOptions XtqlQuery)
           xtdb.indexer.IIndexer
           (xtdb.query IQuerySource PreparedQuery)))

(set! *unchecked-math* :warn-on-boxed)

(defmethod ig/init-key :xtdb/allocator [_ _] (RootAllocator.))
(defmethod ig/halt-key! :xtdb/allocator [_ ^BufferAllocator a]
  (util/close a))

(defmethod ig/init-key :xtdb/default-tz [_ default-tz] default-tz)

(defn- with-after-tx-default [opts]
  (-> opts
      (update :after-tx time/max-tx (get-in opts [:basis :at-tx]))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface IXtdbInternal
  (^java.util.concurrent.CompletableFuture prepareQuery [^java.lang.String query, query-opts])
  (^java.util.concurrent.CompletableFuture prepareQuery [^xtdb.api.query.XtqlQuery query, query-opts]))

(defn- mapify-query-opts-with-defaults [query-opts default-tz latest-submitted-tx default-key-fn]
  ;;not all callers care about all defaulted query opts returned here
  (-> (into {:default-tz default-tz,
             :after-tx latest-submitted-tx
             :key-fn default-key-fn}
            query-opts)
      (update :basis (fn [b] (cond->> b (instance? Basis b) (into {}))))
      (with-after-tx-default)))

(defn- then-execute-prepared-query [prepared-query-fut metrics registry query-opts]
  (util/then-apply
    prepared-query-fut
    (fn [^PreparedQuery prepared-query]
      (let [bound-query (.bind prepared-query query-opts)]
        ;;TODO metrics only currently wrapping openQueryAsync results
        (-> (q/open-cursor-as-stream bound-query query-opts)
            (metrics/wrap-query (:query-timer metrics) registry))))))

(defrecord Node [^BufferAllocator allocator
                 ^IIndexer indexer
                 ^Log log
                 ^IQuerySource q-src, wm-src, scan-emitter
                 default-tz
                 !latest-submitted-tx
                 system, close-fn, registry
                 metrics]
  IXtdb
  (submitTxAsync [this opts tx-ops]
    (let [system-time (some-> opts .getSystemTime)]
      (-> (log/submit-tx& this (vec tx-ops) opts)
          (util/then-apply
            (fn [^TransactionKey tx-key]
              (let [tx-key (cond-> tx-key
                             system-time (.withSystemTime system-time))]

                (swap! !latest-submitted-tx time/max-tx tx-key)
                tx-key))))))

  (^CompletableFuture openQueryAsync [this ^String query, ^QueryOptions query-opts]
   (let [query-opts (mapify-query-opts-with-defaults query-opts default-tz @!latest-submitted-tx #xt/key-fn :snake-case-string)]
     (-> (.prepareQuery this query query-opts)
         (then-execute-prepared-query metrics registry query-opts))))

  (^CompletableFuture openQueryAsync [this ^XtqlQuery query, ^QueryOptions query-opts]
   (let [query-opts (mapify-query-opts-with-defaults query-opts default-tz @!latest-submitted-tx #xt/key-fn :camel-case-string)]
     (-> (.prepareQuery this query query-opts)
         (then-execute-prepared-query metrics registry query-opts))))

  xtp/PStatus
  (latest-submitted-tx [_] @!latest-submitted-tx)
  (status [this]
    {:latest-completed-tx (.latestCompletedTx indexer)
     :latest-submitted-tx (xtp/latest-submitted-tx this)})

  IXtdbInternal
  (^CompletableFuture prepareQuery [_ ^String query, query-opts]
   (let [{:keys [after-tx tx-timeout] :as query-opts}
         (mapify-query-opts-with-defaults query-opts default-tz @!latest-submitted-tx #xt/key-fn :snake-case-string)]
     (-> (.awaitTxAsync indexer after-tx tx-timeout)
         (util/then-apply
           (fn [_]
             (let [plan (.planQuery q-src query wm-src query-opts)]
               (.prepareRaQuery q-src plan wm-src)))))))

  (^CompletableFuture prepareQuery [_ ^XtqlQuery query, query-opts]
   (let [{:keys [after-tx tx-timeout] :as query-opts}
         (mapify-query-opts-with-defaults query-opts default-tz @!latest-submitted-tx #xt/key-fn :camel-case-string)]
     (-> (.awaitTxAsync indexer after-tx tx-timeout)
         (util/then-apply
           (fn [_]
             (let [plan (.planQuery q-src query wm-src query-opts)]
               (.prepareRaQuery q-src plan wm-src)))))))

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
          :log (ig/ref :xtdb/log)
          :default-tz (ig/ref :xtdb/default-tz)
          :q-src (ig/ref :xtdb.query/query-source)
          :scan-emitter (ig/ref :xtdb.operator.scan/scan-emitter)
          :registry (ig/ref :xtdb/meter-registry)}
         opts))

(defn gauge-lag-secs-fn [node]
  (fn []
    (let [{:keys [^TransactionKey latest-completed-tx
                  ^TransactionKey latest-submitted-tx]} (xtp/status node)]
      (if (and latest-completed-tx latest-submitted-tx)
        (let [completed-tx-time (.getSystemTime latest-completed-tx)
              submitted-tx-time (.getSystemTime latest-submitted-tx)]
          (/ (- ^long (inst-ms submitted-tx-time) ^long (inst-ms completed-tx-time)) (long 1e3)))
        0.0))))

(defmethod ig/init-key :xtdb/node [_ {:keys [registry] :as deps}]
  (let [node (map->Node (-> deps
                            (assoc :!latest-submitted-tx (atom nil))
                            (assoc :metrics {:query-timer (metrics/add-timer registry "query.timer"
                                                                             {:description "indicates the timings for queries"})})))]
    ;; TODO seems to create heap memory pressure, disabled for now
    #_(metrics/add-gauge registry "node.tx.lag.seconds"
                         (gauge-lag-secs-fn node))
    node))

(defmethod ig/halt-key! :xtdb/node [_ node]
  (util/try-close node))

(defmethod ig/prep-key :xtdb/modules [_ modules]
  {:node (ig/ref :xtdb/node)
   :modules (vec modules)})

(defmethod ig/init-key :xtdb/modules [_ {:keys [node modules]}]
  (util/with-close-on-catch [!started-modules (HashMap. (count modules))]
    (doseq [^XtdbModule$Factory module modules]
      (.put !started-modules (.getModuleKey module) (.openModule module node)))

    (into {} !started-modules)))

(defmethod ig/halt-key! :xtdb/modules [_ modules]
  (util/close modules))

(defn node-system [^Xtdb$Config opts]
  (-> {:xtdb/node {}
       :xtdb/allocator {}
       :xtdb/indexer {}
       :xtdb.log/watcher {}
       :xtdb.metadata/metadata-manager {}
       :xtdb.operator.scan/scan-emitter {}
       :xtdb.query/query-source {}
       :xtdb/compactor {}
       :xtdb/meter-registry {}
       :xtdb/metrics-server {}

       :xtdb/buffer-pool (.getStorage opts)
       :xtdb.indexer/live-index (.indexer opts)
       :xtdb/log (.getTxLog opts)
       :xtdb/modules (.getModules opts)
       :xtdb/default-tz (.getDefaultTz opts)
       :xtdb.stagnant-log-flusher/flusher (.indexer opts)}
      (doto ig/load-namespaces)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn open-node ^xtdb.api.IXtdb [opts]
  (let [!closing (atom false)
        system (-> (node-system opts)
                   ig/prep
                   ig/init)]

    (-> (:xtdb/node system)
        (assoc :system system
               :close-fn #(when (compare-and-set! !closing false true)
                            (ig/halt! system)
                            #_(println (.toVerboseString ^RootAllocator (:xtdb/allocator system))))))))
