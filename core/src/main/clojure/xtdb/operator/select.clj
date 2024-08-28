(ns xtdb.operator.select
  (:require [clojure.spec.alpha :as s]
            [xtdb.coalesce :as coalesce]
            [xtdb.expression :as expr]
            [xtdb.logical-plan :as lp]
            [xtdb.util :as util]
            [xtdb.types :as types])
  (:import java.util.function.Consumer
           org.apache.arrow.memory.BufferAllocator
           xtdb.ICursor
           xtdb.operator.SelectionSpec
           xtdb.vector.RelationReader))

(defmethod lp/ra-expr :select [_]
  (s/cat :op #{:σ :sigma :select}
         :predicate ::lp/expression
         :relation ::lp/ra-expression))

(set! *unchecked-math* :warn-on-boxed)

(deftype SelectCursor [^BufferAllocator allocator, ^ICursor in-cursor, ^SelectionSpec selector, schema, params]
  ICursor
  (tryAdvance [_ c]
    (let [advanced? (boolean-array 1)]
      (while (and (.tryAdvance in-cursor
                               (reify Consumer
                                 (accept [_ in-rel]
                                   (let [^RelationReader in-rel in-rel]
                                     (when-let [idxs (.select selector allocator in-rel schema params)]
                                       (when-not (zero? (alength idxs))
                                         (.accept c (.select in-rel idxs))
                                         (aset advanced? 0 true)))))))
                  (not (aget advanced? 0))))
      (aget advanced? 0)))

  (close [_]
    (util/try-close in-cursor)))

(defmethod lp/emit-expr :select [{:keys [predicate relation]} {:keys [param-fields] :as args}]
  (lp/unary-expr (lp/emit-expr relation args)
    (fn [inner-fields]
      (let [input-types {:col-types (update-vals inner-fields types/field->col-type)
                         :param-types (update-vals param-fields types/field->col-type)}
            selector (expr/->expression-selection-spec (expr/form->expr predicate input-types) input-types)]
        {:fields inner-fields
         :->cursor (fn [{:keys [allocator params schema]} in-cursor]
                     (-> (SelectCursor. allocator in-cursor selector schema params)
                         (coalesce/->coalescing-cursor allocator)))}))))
