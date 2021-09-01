(ns dev
  "Internal development namespace for Crux. For end-user usage, see
  examples.clj"
  (:require [crux.api :as crux]
            [integrant.core :as i]
            [integrant.repl.state :refer [system]]
            [integrant.repl :as ir :refer [go halt reset reset-all]]
            [crux.io :as cio]
            [crux.lucene]
            [crux.kafka :as k]
            [crux.kafka.embedded :as ek]
            [crux.rocksdb :as rocks]
            [clojure.java.io :as io])
  (:import (crux.api ICruxAPI)
           (java.io Closeable File)
           [ch.qos.logback.classic Level Logger]
           org.slf4j.LoggerFactory))

(defn set-log-level! [ns level]
  (.setLevel ^Logger (LoggerFactory/getLogger (name ns))
             (when level
               (Level/valueOf (name level)))))

(defn get-log-level! [ns]
  (some->> (.getLevel ^Logger (LoggerFactory/getLogger (name ns)))
           (str)
           (.toLowerCase)
           (keyword)))

(defmacro with-log-level [ns level & body]
  `(let [level# (get-log-level! ~ns)]
     (try
       (set-log-level! ~ns ~level)
       ~@body
       (finally
         (set-log-level! ~ns level#)))))

(def dev-node-dir
  (io/file "dev/dev-node"))

(defmethod i/init-key ::crux [_ {:keys [node-opts]}]
  (crux/start-node node-opts))

(defmethod i/halt-key! ::crux [_ ^ICruxAPI node]
  (.close node))

(def standalone-config
  {::crux {:node-opts {:crux/index-store {:kv-store {:crux/module `rocks/->kv-store,
                                                     :db-dir (io/file dev-node-dir "indexes"),
                                                     :block-cache :crux.rocksdb/block-cache}}
                       :crux/document-store {:kv-store {:crux/module `rocks/->kv-store,
                                                        :db-dir (io/file dev-node-dir "documents")
                                                        :block-cache :crux.rocksdb/block-cache}}
                       :crux/tx-log {:kv-store {:crux/module `rocks/->kv-store,
                                                :db-dir (io/file dev-node-dir "tx-log")
                                                :block-cache :crux.rocksdb/block-cache}}
                       :crux.rocksdb/block-cache {:crux/module `rocks/->lru-block-cache
                                                  :cache-size (* 128 1024 1024)}
                       :crux.metrics.jmx/reporter {}
                       :crux.http-server/server {}
                       :crux.lucene/lucene-store {:db-dir (io/file dev-node-dir "lucene")}}}})

(defmethod i/init-key ::embedded-kafka [_ {:keys [kafka-port kafka-dir]}]
  {:embedded-kafka (ek/start-embedded-kafka #::ek{:zookeeper-data-dir (io/file kafka-dir "zk-data")
                                                  :zookeeper-port (cio/free-port)
                                                  :kafka-log-dir (io/file kafka-dir "kafka-log")
                                                  :kafka-port kafka-port})
   :meta-properties-file (io/file kafka-dir "kafka-log/meta.properties")})

(defmethod i/halt-key! ::embedded-kafka [_ {:keys [^Closeable embedded-kafka ^File meta-properties-file ]}]
  (.close embedded-kafka)
  (.delete meta-properties-file))

(def embedded-kafka-config
  (let [kafka-port (cio/free-port)]
    {::embedded-kafka {:kafka-port kafka-port
                       :kafka-dir (io/file dev-node-dir "kafka")}
     ::crux {:ek (i/ref ::embedded-kafka)
             :node-opts {::k/kafka-config {:bootstrap-servers (str "http://localhost:" kafka-port)}
                         :crux.http-server/server {}
                         :crux/index-store {:kv-store {:crux/module `rocks/->kv-store
                                                       :db-dir (io/file dev-node-dir "ek-indexes")}}
                         :crux/document-store {:crux/module `k/->document-store,
                                               :kafka-config ::k/kafka-config
                                               :local-document-store {:kv-store {:crux/module `rocks/->kv-store,
                                                                                 :db-dir (io/file dev-node-dir "ek-documents")}}}
                         :crux/tx-log {:crux/module `k/->tx-log, :kafka-config ::k/kafka-config}}}}))

;; swap for `embedded-kafka-config` to use embedded-kafka
(ir/set-prep! (fn [] standalone-config))

(defn crux-node []
  (::crux system))
