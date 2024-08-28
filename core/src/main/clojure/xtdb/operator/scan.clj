(ns xtdb.operator.scan
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.bitemporal :as bitemp]
            [xtdb.bloom :as bloom]
            [xtdb.buffer-pool :as bp]
            [xtdb.expression :as expr]
            [xtdb.expression.metadata :as expr.meta]
            xtdb.indexer.live-index
            [xtdb.information-schema :as info-schema]
            [xtdb.logical-plan :as lp]
            [xtdb.metadata :as meta]
            xtdb.object-store
            [xtdb.time :as time]
            [xtdb.trie :as trie :refer [MergePlanPage]]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr]
            [xtdb.vector.writer :as vw])
  (:import (clojure.lang MapEntry)
           (com.carrotsearch.hppc IntArrayList)
           (java.io Closeable)
           java.nio.ByteBuffer
           (java.nio.file Path)
           (java.util ArrayList Comparator HashMap Iterator LinkedList Map PriorityQueue Stack)
           (java.util.function BiFunction IntPredicate Predicate)
           (java.util.stream IntStream)
           (org.apache.arrow.memory ArrowBuf BufferAllocator)
           [org.apache.arrow.memory.util ArrowBufPointer]
           (org.apache.arrow.vector VectorLoader)
           (org.apache.arrow.vector.types.pojo Field FieldType)
           [org.roaringbitmap.buffer MutableRoaringBitmap]
           xtdb.api.TransactionKey
           (xtdb.arrow VectorIndirection VectorReader)
           (xtdb.bitemporal IRowConsumer Polygon)
           xtdb.IBufferPool
           xtdb.ICursor
           (xtdb.metadata IMetadataManager ITableMetadata)
           xtdb.operator.SelectionSpec
           (xtdb.trie ArrowHashTrie$Leaf EventRowPointer EventRowPointer$Arrow HashTrie
                      HashTrieKt LiveHashTrie$Leaf MergePlanNode MergePlanTask)
           (xtdb.util TemporalBounds TemporalDimension)
           (xtdb.vector IMultiVectorRelationFactory IRelationWriter IVectorReader IVectorWriter IndirectMultiVectorReader RelationReader RelationWriter)
           (xtdb.watermark ILiveTableWatermark IWatermarkSource Watermark)))

(s/def ::table symbol?)

;; TODO be good to just specify a single expression here and have the interpreter split it
;; into metadata + col-preds - the former can accept more than just `(and ~@col-preds)
(defmethod lp/ra-expr :scan [_]
  (s/cat :op #{:scan}
         :scan-opts (s/keys :req-un [::table]
                            :opt-un [::lp/for-valid-time ::lp/for-system-time])
         :columns (s/coll-of (s/or :column ::lp/column
                                   :select ::lp/column-expression))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface IScanEmitter
  (scanFields [^xtdb.watermark.Watermark wm, scan-cols])
  (emitScan [scan-expr scan-fields param-fields]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->scan-cols [{:keys [columns], {:keys [table]} :scan-opts}]
  (for [[col-tag col-arg] columns]
    [table (case col-tag
             :column col-arg
             :select (key (first col-arg)))]))

(def ^:dynamic *column->pushdown-bloom* {})

(defn- ->temporal-bounds [^RelationReader params, {:keys [^TransactionKey at-tx]}, {:keys [for-valid-time for-system-time]}]
  (letfn [(->time-μs [[tag arg]]
            (case tag
              :literal (-> arg
                           (time/sql-temporal->micros (.getZone expr/*clock*)))
              :param (some-> (-> (.readerForName params (name arg))
                                 (.getObject 0))
                             (time/sql-temporal->micros (.getZone expr/*clock*)))
              :now (-> (.instant expr/*clock*)
                       (time/instant->micros))))
          (apply-constraint [constraint]
            (if-let [[tag & args] constraint]
              (case tag
                :at (let [[at] args
                          at-μs (->time-μs at)]
                      (TemporalDimension/at at-μs))

                ;; overlaps [time-from time-to]
                :in (let [[from to] args]
                      (TemporalDimension/in (->time-μs (or from [:now]))
                                            (some-> to ->time-μs)))

                :between (let [[from to] args]
                           (TemporalDimension/between (->time-μs (or from [:now]))
                                                      (some-> to ->time-μs)))

                :all-time (TemporalDimension.))
              (TemporalDimension.)))]

    (let [^TemporalDimension sys-dim (apply-constraint for-system-time)
          bounds (TemporalBounds. (apply-constraint for-valid-time) sys-dim)]
      ;; we further constrain bases on tx
      (when-let [system-time (some-> at-tx (.getSystemTime) time/instant->micros)]
        (.setUpper sys-dim (min (inc system-time) (.getUpper sys-dim)))

        (when-not for-system-time
          (.setLower (.getSystemTime bounds) system-time)))

      bounds)))

(defn tables-with-cols [^IWatermarkSource wm-src]
  (with-open [^Watermark wm (.openWatermark wm-src)]
    (.schema wm)))

(defn temporal-column? [col-name]
  (contains? #{"xt$system_from" "xt$system_to" "xt$valid_from" "xt$valid_to"}
             col-name))

(defn rels->multi-vector-rel-factory ^xtdb.vector.IMultiVectorRelationFactory [leaf-rels, ^BufferAllocator allocator, col-names]
  (let [put-rdrs (mapv (fn [^RelationReader rel]
                         [(.rowCount rel) (-> (.readerForName rel "op") (.legReader "put"))])
                       leaf-rels)
        reader-indirection (IntArrayList.)
        vector-indirection (IntArrayList.)]
    (letfn [(->indirect-multi-vec [col-name reader-selection vector-selection]
              (let [readers (ArrayList.)]
                (if (= col-name "xt$iid")
                  (doseq [^RelationReader leaf-rel leaf-rels]
                    (.add readers (.readerForName leaf-rel "xt$iid")))

                  (doseq [[row-count ^IVectorReader put-rdr] put-rdrs]
                    (if-let [rdr (some-> (.structKeyReader put-rdr col-name)
                                         (.withName col-name))]
                      (.add readers rdr)
                      (.add readers (vr/->absent-col col-name allocator row-count)))))
                (IndirectMultiVectorReader. readers reader-selection vector-selection)))]
      (reify IMultiVectorRelationFactory
        (accept [_ rdrIdx vecIdx]
          (.add reader-indirection rdrIdx)
          (.add vector-indirection vecIdx))
        (realize [_]
          (let [reader-selection (VectorIndirection/selection (.toArray reader-indirection))
                vector-selection (VectorIndirection/selection (.toArray vector-indirection))]
            (RelationReader/from (mapv #(->indirect-multi-vec % reader-selection vector-selection) col-names))))))))

(defn- ->content-rel-factory ^xtdb.vector.IMultiVectorRelationFactory [leaf-rdrs allocator content-col-names]
  (rels->multi-vector-rel-factory leaf-rdrs allocator content-col-names))

(defn- ->bitemporal-consumer ^xtdb.bitemporal.IRowConsumer [^IRelationWriter out-rel, col-names]
  (letfn [(writer-for [col-name nullable?]
            (when (contains? col-names col-name)
              (.colWriter out-rel col-name (FieldType. nullable? (types/->arrow-type types/temporal-col-type) nil))))]

    (let [^IVectorWriter valid-from-wtr (writer-for "xt$valid_from" false)
          ^IVectorWriter valid-to-wtr (writer-for "xt$valid_to" true)
          ^IVectorWriter sys-from-wtr (writer-for "xt$system_from" false)
          ^IVectorWriter sys-to-wtr (writer-for "xt$system_to" true)]

      (reify IRowConsumer
        (accept [_ _idx valid-from valid-to sys-from sys-to]
          (some-> valid-from-wtr (.writeLong valid-from))

          (when valid-to-wtr
            (if (= Long/MAX_VALUE valid-to)
              (.writeNull valid-to-wtr)
              (.writeLong valid-to-wtr valid-to)))

          (some-> sys-from-wtr (.writeLong sys-from))

          (when sys-to-wtr
            (if (= Long/MAX_VALUE sys-to)
              (.writeNull sys-to-wtr)
              (.writeLong sys-to-wtr sys-to))))))))

(defn iid-selector [^ByteBuffer iid-bb]
  (reify SelectionSpec
    (select [_ allocator rel-rdr _schema _params]
      (with-open [arrow-buf (util/->arrow-buf-view allocator iid-bb)]
        (let [iid-ptr (ArrowBufPointer. arrow-buf 0 (.capacity iid-bb))
              ptr (ArrowBufPointer.)
              iid-rdr (.readerForName rel-rdr "xt$iid")
              value-count (.valueCount iid-rdr)]
          (if (pos-int? value-count)
            ;; lower-bound
            (loop [left 0 right (dec value-count)]
              (if (= left right)
                (if (= iid-ptr (.getPointer iid-rdr left ptr))
                  ;; upper bound
                  (loop [right left]
                    (if (or (>= right value-count) (not= iid-ptr (.getPointer iid-rdr right ptr)))
                      (.toArray (IntStream/range left right))
                      (recur (inc right))))
                  (int-array 0))
                (let [mid (quot (+ left right) 2)]
                  (if (<= (.compareTo iid-ptr (.getPointer iid-rdr mid ptr)) 0)
                    (recur left mid)
                    (recur (inc mid) right)))))
            (int-array 0)))))))


(defrecord VSRCache [^IBufferPool buffer-pool, ^BufferAllocator allocator, ^Map free, ^Map used]
  Closeable
  (close [_]
    (util/close free)
    (util/close used)))

(defn ->vsr-cache [buffer-pool allocator]
  (->VSRCache buffer-pool allocator (HashMap.) (HashMap.)))

(defn reset-vsr-cache [{:keys [^Map free, ^Map used]}]
  (doseq [^MapEntry entry (.entrySet used)]
    (.merge free (key entry) (val entry) (reify BiFunction
                                           (apply [_ free-entries used-entries]
                                             (.addAll ^Stack free-entries ^Stack used-entries)
                                             free-entries))))
  (.clear used))

(defn cache-vsr [{:keys [^Map free, ^Map used, buffer-pool, allocator]} ^Path trie-leaf-file]
  (let [vsr (let [^Stack free-entries (.get free trie-leaf-file)]
              (if (and free-entries (> (.size free-entries) 0))
                (.pop free-entries)
                (bp/open-vsr buffer-pool trie-leaf-file allocator)))
        ^Stack used-entries (.computeIfAbsent used trie-leaf-file
                                              (fn [_]
                                                (Stack.)))]
    (.push used-entries vsr)
    vsr))

(defrecord LeafPointer [ev-ptr rel-idx])

(deftype TrieCursor [^BufferAllocator allocator, ^Iterator merge-tasks, ^IRelationWriter out-rel
                     col-names, ^Map col-preds,
                     ^TemporalBounds temporal-bounds
                     schema, params, vsr-cache, buffer-pool]
  ICursor
  (tryAdvance [_ c]
    (let [!advanced? (boolean-array 1)]
      (while (and (not (aget !advanced? 0))
                  (.hasNext merge-tasks))
        (let [{:keys [leaves path]} (.next merge-tasks)
              is-valid-ptr (ArrowBufPointer.)]
          (reset-vsr-cache vsr-cache)
          (with-open [out-rel (vw/->rel-writer allocator)]
            (let [^SelectionSpec iid-pred (get col-preds "xt$iid")
                  merge-q (PriorityQueue. (Comparator/comparing #(.ev_ptr ^LeafPointer %) (EventRowPointer/comparator)))
                  calculate-polygon (bitemp/polygon-calculator temporal-bounds)
                  bitemp-consumer (->bitemporal-consumer out-rel col-names)
                  leaf-rdrs (for [leaf leaves
                                  :let [^RelationReader data-rdr (trie/load-page leaf buffer-pool vsr-cache)]]
                              (cond-> data-rdr
                                iid-pred (.select (.select iid-pred allocator data-rdr {} params))))
                  [temporal-cols content-cols] ((juxt filter remove) temporal-column? col-names)
                  content-rel-factory (->content-rel-factory leaf-rdrs allocator content-cols)]

              (doseq [[idx leaf-rdr] (map-indexed vector leaf-rdrs)
                      :let [ev-ptr (EventRowPointer$Arrow. leaf-rdr path)]]
                (when (.isValid ev-ptr is-valid-ptr path)
                  (.add merge-q (->LeafPointer ev-ptr idx))))

              (loop []
                (when-let [^LeafPointer q-obj (.poll merge-q)]
                  (let [^EventRowPointer ev-ptr (.ev_ptr q-obj)]
                    (when-let [^Polygon polygon (calculate-polygon ev-ptr)]
                      (when (= "put" (.getOp ev-ptr))
                        (let [sys-from (.getSystemFrom ev-ptr)
                              idx (.getIndex ev-ptr)]
                          (dotimes [i (.getValidTimeRangeCount polygon)]
                            (let [valid-from (.getValidFrom polygon i)
                                  valid-to (.getValidTo polygon i)
                                  sys-to (.getSystemTo polygon i)]
                              (when (and (.intersects temporal-bounds valid-from valid-to sys-from sys-to)
                                         (not (= valid-from valid-to))
                                         (not (= sys-from sys-to)))
                                (.startRow out-rel)
                                (.accept content-rel-factory (.rel-idx q-obj) idx)
                                (.accept bitemp-consumer idx valid-from valid-to sys-from sys-to)
                                (.endRow out-rel)))))))

                    (.nextIndex ev-ptr)
                    (when (.isValid ev-ptr is-valid-ptr path)
                      (.add merge-q q-obj))
                    (recur))))

              (let [^RelationReader rel (cond-> (.realize content-rel-factory)
                                          (or (empty? (seq content-cols)) (seq temporal-cols))
                                          (vr/concat-rels (vw/rel-wtr->rdr out-rel)))
                    ^RelationReader rel (reduce (fn [^RelationReader rel ^SelectionSpec col-pred]
                                                  (.select rel (.select col-pred allocator rel schema params)))
                                                rel
                                                (vals (dissoc col-preds "xt$iid")))]
                (when (pos? (.rowCount rel))
                  (.accept c rel)
                  (aset !advanced? 0 true)))))))

      (aget !advanced? 0)))

  (close [_]
    (util/close vsr-cache)
    (util/close out-rel)))

(defn- eid-select->eid [eid-select]
  (cond (= 'xt$id (second eid-select))
        (nth eid-select 2)

        (= 'xt$id (nth eid-select 2))
        (second eid-select)))

(defn selects->iid-byte-buffer ^ByteBuffer [selects ^RelationReader params-rel]
  (when-let [eid-select (get selects "xt$id")]
    (when (= '= (first eid-select))
      (when-let [eid (eid-select->eid eid-select)]
        (cond
          (and (s/valid? ::lp/value eid) (trie/valid-iid? eid))
          (trie/->iid eid)

          (s/valid? ::lp/param eid)
          (let [eid-rdr (.readerForName params-rel (name eid))]
            (when (= 1 (.valueCount eid-rdr))
              (let [eid (.getObject eid-rdr 0)]
                (when (trie/valid-iid? eid)
                  (trie/->iid eid))))))))))

(defn filter-pushdown-bloom-page-idx-pred ^IntPredicate [^ITableMetadata table-metadata ^String col-name]
  (when-let [^MutableRoaringBitmap pushdown-bloom (get *column->pushdown-bloom* (symbol col-name))]
    (let [metadata-rdr (VectorReader/from (.metadataReader table-metadata))
          bloom-rdr (-> (.keyReader metadata-rdr "columns")
                        (.elementReader)
                        (.keyReader "bloom"))]
      (reify IntPredicate
        (test [_ page-idx]
          (boolean
           (let [bloom-vec-idx (.rowIndex table-metadata col-name page-idx)]
             (and (>= bloom-vec-idx 0)
                  (not (nil? (.getObject bloom-rdr bloom-vec-idx)))
                  (MutableRoaringBitmap/intersects pushdown-bloom
                                                   (bloom/bloom->bitmap bloom-rdr bloom-vec-idx))))))))))

(defn ->path-pred [^ArrowBuf iid-arrow-buf]
  (when iid-arrow-buf
    (let [iid-ptr (ArrowBufPointer. iid-arrow-buf 0 (.capacity iid-arrow-buf))]
      (reify Predicate
        (test [_ path]
          (zero? (HashTrie/compareToPath iid-ptr path)))))))

(defrecord ArrowMergePlanPage [data-file-path ^IntPredicate page-idx-pred ^long page-idx ^ITableMetadata table-metadata]
  MergePlanPage
  (load-page [_mpg buffer-pool vsr-cache]
    (util/with-open [rb (bp/open-record-batch buffer-pool data-file-path page-idx)]
      (let [vsr (cache-vsr vsr-cache data-file-path)
            loader (VectorLoader. vsr)]
        (.load loader rb)
        (vr/<-root vsr))))

  (test-metadata [_mpg]
    (.test page-idx-pred page-idx))

  (temporal-bounds [_mpg] (.temporalBounds table-metadata (int page-idx))))

(def ^:private non-constraint-bounds (TemporalBounds.))

(defrecord LiveMergePlanPage [^RelationReader live-rel trie ^LiveHashTrie$Leaf leaf]
  MergePlanPage
  (load-page [_mpg _buffer-pool _vsr-cache]
    (.select live-rel (.mergeSort leaf trie)))

  (test-metadata [_mpg] true)

  (temporal-bounds [_msg] non-constraint-bounds))

(defmethod ig/prep-key ::scan-emitter [_ opts]
  (merge opts
         {:metadata-mgr (ig/ref ::meta/metadata-manager)
          :buffer-pool (ig/ref :xtdb/buffer-pool)}))

(defmethod ig/init-key ::scan-emitter [_ {:keys [^IMetadataManager metadata-mgr, ^IBufferPool buffer-pool]}]
  (reify IScanEmitter
    (scanFields [_ wm scan-cols]
      (letfn [(->field [[table col-name]]
                (let [table (str table)
                      col-name (str col-name)]
                  ;; TODO move to fields here
                  (-> (or (some-> (types/temporal-col-types col-name) types/col-type->field)
                          (if-let [info-field (get-in info-schema/derived-tables [(symbol table) col-name])]
                            info-field
                            (types/merge-fields (.columnField metadata-mgr table col-name)
                                                (some-> (.liveIndex wm)
                                                        (.liveTable table)
                                                        (.columnField col-name)))))
                      (types/field-with-name col-name))))]
        (->> scan-cols
             (into {} (map (juxt identity ->field))))))

    (emitScan [_ {:keys [columns], {:keys [table] :as scan-opts} :scan-opts} scan-fields param-fields]
      (let [col-names (->> columns
                           (into #{} (map (fn [[col-type arg]]
                                            (case col-type
                                              :column arg
                                              :select (key (first arg)))))))

            fields (->> col-names
                        (into {} (map (juxt identity
                                            (fn [col-name]
                                              (get scan-fields [table col-name]))))))

            col-names (into #{} (map str) col-names)

            table-name (str table)

            selects (->> (for [[tag arg] columns
                               :when (= tag :select)
                               :let [[col-name pred] (first arg)]]
                           (MapEntry/create (str col-name) pred))
                         (into {}))

            col-preds (->> (for [[col-name select-form] selects]
                             ;; for temporal preds, we may not need to re-apply these if they can be represented as a temporal range.
                             (let [input-types {:col-types (update-vals fields types/field->col-type)
                                                :param-types (update-vals param-fields types/field->col-type)}]
                               (MapEntry/create col-name
                                                (expr/->expression-selection-spec (expr/form->expr select-form input-types)
                                                                                  input-types))))
                           (into {}))

            metadata-args (vec (for [[col-name select] selects
                                     :when (not (types/temporal-column? col-name))]
                                 select))

            row-count (->> (for [{:keys [tables]} (vals (.chunksMetadata metadata-mgr))
                                 :let [{:keys [row-count]} (get tables table-name)]
                                 :when row-count]
                             row-count)
                           (reduce +))]

        {:fields fields
         :stats {:row-count row-count}
         :->cursor (fn [{:keys [allocator, ^Watermark watermark, basis, schema, params]}]
                     (if-let [derived-table-schema (info-schema/derived-tables table)]
                       (info-schema/->cursor allocator derived-table-schema table col-names col-preds schema params metadata-mgr watermark)
                       (let [iid-bb (selects->iid-byte-buffer selects params)
                             col-preds (cond-> col-preds
                                         iid-bb (assoc "xt$iid" (iid-selector iid-bb)))
                             metadata-pred (expr.meta/->metadata-selector (cons 'and metadata-args) (update-vals fields types/field->col-type) params)
                             scan-opts (-> scan-opts
                                           (update :for-valid-time
                                                   (fn [fvt]
                                                     (or fvt [:at [:now :now]]))))
                             ^ILiveTableWatermark live-table-wm (some-> (.liveIndex watermark) (.liveTable table-name))
                             table-path (util/table-name->table-path table-name)
                             current-meta-files (->> (trie/list-meta-files buffer-pool table-path)
                                                     (trie/current-trie-files))
                             temporal-bounds (->temporal-bounds params basis scan-opts)]
                         (util/with-open [iid-arrow-buf (when iid-bb (util/->arrow-buf-view allocator iid-bb))]
                           (let [merge-tasks (util/with-open [table-metadatas (LinkedList.)]
                                               (let [segments (cond-> (mapv (fn [meta-file-path]
                                                                              (let [{:keys [trie] :as table-metadata} (.openTableMetadata metadata-mgr meta-file-path)]
                                                                                (.add table-metadatas table-metadata)
                                                                                (into (trie/->Segment trie)
                                                                                      {:data-file-path (trie/->table-data-file-path table-path
                                                                                                                                    (:trie-key (trie/parse-trie-file-path meta-file-path)))
                                                                                       :page-idx-pred (reduce (fn [^IntPredicate page-idx-pred col-name]
                                                                                                                (if-let [bloom-page-idx-pred (filter-pushdown-bloom-page-idx-pred table-metadata col-name)]
                                                                                                                  (.and page-idx-pred bloom-page-idx-pred)
                                                                                                                  page-idx-pred))
                                                                                                              (.build metadata-pred table-metadata)
                                                                                                              col-names)
                                                                                       :table-metadata table-metadata})))
                                                                            current-meta-files)

                                                                live-table-wm (conj (trie/->Segment (.liveTrie live-table-wm))))]
                                                 (->> (HashTrieKt/toMergePlan segments (->path-pred iid-arrow-buf) temporal-bounds)
                                                      (into [] (keep (fn [^MergePlanTask mpt]
                                                                       (when-let [leaves (trie/->merge-task
                                                                                          (for [^MergePlanNode mpn (.getMpNodes mpt)
                                                                                                :let [{:keys [data-file-path table-metadata page-idx-pred]} (.getSegment mpn)
                                                                                                      node (.getNode mpn)]]
                                                                                            (if data-file-path
                                                                                              (->ArrowMergePlanPage data-file-path
                                                                                                                    page-idx-pred
                                                                                                                    (.getDataPageIndex ^ArrowHashTrie$Leaf node)
                                                                                                                    table-metadata)
                                                                                              (->LiveMergePlanPage (.liveRelation live-table-wm) (.liveTrie live-table-wm) node)))
                                                                                          temporal-bounds)]
                                                                         {:path (.getPath mpt)
                                                                          :leaves leaves})))))))]

                             (util/with-close-on-catch [out-rel (RelationWriter. allocator
                                                                                 (for [^Field field (vals fields)]
                                                                                   (vw/->writer (.createVector field allocator))))]
                               (->TrieCursor allocator (.iterator ^Iterable merge-tasks) out-rel
                                             col-names col-preds
                                             temporal-bounds
                                             schema
                                             params
                                             (->vsr-cache buffer-pool allocator)
                                             buffer-pool)))))))}))))

(defmethod lp/emit-expr :scan [scan-expr {:keys [^IScanEmitter scan-emitter scan-fields, param-fields]}]
  (.emitScan scan-emitter scan-expr scan-fields param-fields))
