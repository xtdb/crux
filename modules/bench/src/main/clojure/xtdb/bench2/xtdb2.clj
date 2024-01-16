(ns xtdb.bench2.xtdb2
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.bench2 :as b]
            [xtdb.bench2.measurement :as bm]
            [xtdb.node :as xtn]
            [xtdb.protocols :as xtp]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (io.micrometer.core.instrument MeterRegistry Timer)
           (java.io Closeable File)
           (java.nio.file Path)
           (java.time Duration InstantSource)
           (java.util.concurrent.atomic AtomicLong)
           (xtdb.api TransactionKey)))

(set! *warn-on-reflection* false)

(defn install-tx-fns [worker fns]
  (->> (for [[id fn-def] fns]
         (xt/put-fn id fn-def))
       (xt/submit-tx (:sut worker))))

(defn generate
  ([worker table f n]
   (let [doc-seq (remove nil? (repeatedly (long n) (partial f worker)))
         partition-count 512]
     (doseq [chunk (partition-all partition-count doc-seq)]
       (xt/submit-tx (:sut worker) (mapv (partial xt/put table) chunk))))))

(defn install-proxy-node-meters!
  [^MeterRegistry meter-reg]
  (let [timer #(-> (Timer/builder %)
                   (.minimumExpectedValue (Duration/ofNanos 1))
                   (.maximumExpectedValue (Duration/ofMinutes 2))
                   (.publishPercentiles (double-array bm/percentiles))
                   (.register meter-reg))]
    {:submit-tx-timer (timer "node.submit-tx")
     :query-timer (timer "node.query")}))

(defn bench-proxy ^Closeable [node ^MeterRegistry meter-reg]
  (let [last-submitted (atom nil)
        last-completed (atom nil)

        submit-counter (AtomicLong.)
        indexed-counter (AtomicLong.)

        _
        (doto meter-reg
          #_(.gauge "node.tx" ^Iterable [(Tag/of "event" "submitted")] submit-counter)
          #_(.gauge "node.tx" ^Iterable [(Tag/of "event" "indexed")] indexed-counter))


        fn-gauge (partial bm/new-fn-gauge meter-reg)

        ;; on-indexed
        ;; (fn [{:keys [submitted-tx, doc-ids, av-count, bytes-indexed] :as event}]
        ;;   (reset! last-completed submitted-tx)
        ;;   (.getAndIncrement indexed-counter)
        ;;   nil)

        compute-lag-nanos #_(partial compute-nanos node last-completed last-submitted)
        (fn []
          (let [{:keys [^TransactionKey latest-completed-tx] :as res} (xt/status node)]
            (or
             (when-some [[fut ms] @last-submitted]
               (let [tx-id (.getTxId ^TransactionKey @fut)
                     completed-tx-id (.getTxId latest-completed-tx)
                     completed-tx-time (.getSystemTime latest-completed-tx)]
                 (when (and (some? completed-tx-id)
                            (some? completed-tx-time)
                            (< completed-tx-id tx-id))
                   (* (long 1e6) (- ms (inst-ms completed-tx-time))))))
             0)))

        compute-lag-abs
        (fn []
          (let [{:keys [^TransactionKey latest-completed-tx] :as res} (xt/status node)]
            (or
             (when-some [[fut _] @last-submitted]
               (let [tx-id (.getTxId ^TransactionKey @fut)
                     completed-tx-id (.getTxId latest-completed-tx)]
                 (when (some? completed-tx-id)
                   (- tx-id completed-tx-id))))
             0)))]


    (fn-gauge "node.tx.lag seconds" (comp #(/ % 1e9) compute-lag-nanos) {:unit "seconds"})
    (fn-gauge "node.tx.lag tx-id" compute-lag-abs )

    (reify
      xtp/PNode
      (open-query& [_ query args] (xtp/open-query& node query args))
      (latest-submitted-tx [_] (xtp/latest-submitted-tx node))

      xtp/PStatus
      (status [_]
        (let [{:keys [latest-completed-tx] :as res} (xt/status node)]
          (reset! last-completed latest-completed-tx)
          res))

      xtp/PSubmitNode
      (submit-tx& [_ tx-ops]
        (let [ret (xtp/submit-tx& node tx-ops)]
          (reset! last-submitted [ret (System/currentTimeMillis)])
          ;; (.incrementAndGet submit-counter)
          ret))

      (submit-tx& [_ tx-ops opts]
        (let [ret (xt/submit-tx& node tx-ops opts)]
          (reset! last-submitted [ret (System/currentTimeMillis)])
          ;; (.incrementAndGet submit-counter)
          ret))

      Closeable
      ;; o/w some stage closes the node for later stages
      (close [_] nil #_(.close node)))))

(defn wrap-task [task f]
  (let [{:keys [stage]} task]
    (-> task
        (bm/wrap-task (if stage
                        (fn instrumented-stage [worker]
                          (if bm/*stage-reg*
                            (with-open [node-proxy (bench-proxy (:sut worker) bm/*stage-reg*)]
                              (f (assoc worker :sut node-proxy)))
                            (f worker)))
                        f)))))

(defn run-benchmark [{:keys [node-opts benchmark-type benchmark-opts]}]
  (let [benchmark (case benchmark-type
                    :auctionmark
                    ((requiring-resolve 'xtdb.bench2.auctionmark/benchmark) benchmark-opts)
                    #_#_:tpch
                    ((requiring-resolve 'xtdb.bench2.tpch/benchmark) benchmark-opts)
                    #_#_:trace (trace benchmark-opts))
        benchmark-fn (b/compile-benchmark
                      benchmark
                      ;; @(requiring-resolve `xtdb.bench.measurement/wrap-task)
                      (fn [task f] (wrap-task task f)))]
    (with-open [node (tu/->local-node node-opts)]
      (benchmark-fn node))))

(defn delete-directory-recursive
  "Recursively delete a directory."
  [^java.io.File file]
  (when (.isDirectory file)
    (run! delete-directory-recursive (.listFiles file)))
  (io/delete-file file))

(defn node-dir->config [^File node-dir]
  (let [^Path path (.toPath node-dir)]
    {:log [:local {:path (.resolve path "log")}]
     :storage [:local {:path (.resolve path "objects")}]}))

(defn- only-oltp-stage [report]
  (let [stage-filter #(filter (comp #{:oltp} :stage) %)]
    (-> report
        (update :stages stage-filter)
        (update :metrics stage-filter))))

(defn run-auctionmark [{:keys [output-file node-dir load-phase duration]
                        :or {node-dir "dev/auctionmark-run"
                             duration "PT30S"} :as opts}]
  (let [output-file (or output-file (str "auctionmark-" duration ".edn"))
        node-dir (.toPath (io/file node-dir))]
    (when load-phase
      (util/delete-dir node-dir))
    (let [report (-> (run-benchmark
                      {:node-opts {:node-dir node-dir
                                   :instant-src (InstantSource/system)}
                       :benchmark-type :auctionmark
                       :benchmark-opts (assoc opts :sync true)})
                     only-oltp-stage)]
      (spit (io/file output-file) report))))

(def cli-options
  [[nil "--load-phase LOAD-PHASE" :parse-fn #(not (or (= % "false") (= % "nil")))]
   [nil "--output-file OUTPUT-FILE"]
   [nil "--node-dir NODE-DIR"]
   [nil "--duration DURATION" :validate [#(try (Duration/parse %) true (catch Throwable _t false))
                                         "Incorrect duration period"]]
   [nil "--threads THREADS" :parse-fn #(Long/parseLong %)]
   [nil "--scale-factor" :parse-fn #(Double/parseDouble %)]])

(defn -main [& args]
  (let [{:keys [options _arguments errors]} (cli/parse-opts args cli-options)]
    (log/debug "Auctionmark run opts:" options)
    (if (seq errors)
      (binding [*out* *err*]
        (doseq [error errors]
          (println error))
        (System/exit 1))
      (run-auctionmark options))))

(comment

  ;; ======
  ;; Running in process
  ;; ======

  (def run-duration "PT5S")
  (def run-duration "PT10S")
  (def run-duration "PT30S")
  (def run-duration "PT2M")
  (def run-duration "PT10M")

  (def node-dir (io/file "dev/dev-node"))
  (delete-directory-recursive node-dir)

  ;; The load-phase is essentially required once to setup some initial data,
  ;; but can be ignored on subsequent runs.
  ;; run-benchmark clears up the node it creates (but not the data),
  ;; hence needing to create a new one to test single point queries

  (def report-core2
    (run-benchmark
     {:node-opts {:node-dir (.toPath node-dir)
                  :instant-src (InstantSource/system)}
      :benchmark-type :auctionmark
      :benchmark-opts {:duration run-duration :load-phase true
                       :scale-factor 0.1 :threads 1}}))

  ;;;;;;;;;;;;;
  ;; Viewing Reports
  ;;;;;;;;;;;;;

  (spit (io/file "core2-30s.edn") report-core2)
  (def report-core2 (clojure.edn/read-string (slurp (io/file "core2-30s.edn"))))

  (require 'xtdb.bench2.report)
  (xtdb.bench2.report/show-html-report
   (xtdb.bench2.report/vs
    "core2"
    report-core2))

  (def report-rocks (clojure.edn/read-string (slurp (io/file "../xtdb/core1-rocks-30s.edn"))))

  (xtdb.bench2.report/show-html-report
   (xtdb.bench2.report/vs
    "core2"
    report-core2
    "rocks"
    report-rocks))

  ;;;;;;;;;;;;;
  ;; testing single point queries
  ;;;;;;;;;;;;;

  (def node (xtn/start-node (node-dir->config node-dir)))

  (def get-item-query '(from :item [{:xt/id i_id, :i_status :open}
                                    i_u_id i_initial_price i_current_price]) )
  ;; ra for the above
  (def ra-query
    '[:scan
      {:table item :for-valid-time [:at :now], :for-system-time nil}
      [{i_status (= i_status :open)}
       i_u_id
       i_current_price
       i_initial_price
       {xt/id (= xt/id ?i_id)}
       id]])

  (def open-ids (->> (xt/q node '(from :item [{:xt/id i :i_status :open}]))
                     (map :i)))

  (def q  (fn [open-id]
            (tu/query-ra ra-query {:node node
                                   :params {'?i_id open-id}})))
  ;; ra query
  (time
   (tu/with-allocator
     #(doseq [id (take 1000 (shuffle open-ids))]
        (q id))))

  ;; datalog query
  (time
   (doseq [id (take 1000 (shuffle open-ids))]
     (xt/q node [get-item-query id]))))
