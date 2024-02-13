(ns xtdb.expression.temporal
  (:require [clojure.string :as str]
            [xtdb.error :as err]
            [xtdb.expression :as expr]
            [xtdb.time :as time]
            [xtdb.types :as types])
  (:import (java.time Duration Instant LocalDate LocalDateTime LocalTime Period ZoneId ZoneOffset ZonedDateTime)
           (java.time.temporal ChronoField ChronoUnit)
           [java.util Map]
           (org.apache.arrow.vector PeriodDuration)
           [xtdb DateTruncator]
           [xtdb.vector IValueReader ValueBox]))

(set! *unchecked-math* :warn-on-boxed)

;;;; units

(defn- with-conversion [form from-unit to-unit]
  (if (= from-unit to-unit)
    form
    (let [from-hz (types/ts-units-per-second from-unit)
          to-hz (types/ts-units-per-second to-unit)]
      (if (> to-hz from-hz)
        `(Math/multiplyExact ~form ~(quot to-hz from-hz))
        `(quot ~form ~(quot from-hz to-hz))))))

(defn- with-arg-unit-conversion [unit1 unit2 ->ret-type ->call-code]
  (if (= unit1 unit2)
    {:return-type (->ret-type unit1), :->call-code ->call-code}

    (let [res-unit (types/smallest-ts-unit unit1 unit2)]
      {:return-type (->ret-type res-unit),
       :->call-code (fn [[arg1 arg2]]
                      (->call-code [(with-conversion arg1 unit1 res-unit) (with-conversion arg2 unit2 res-unit)]))})))

(defn- with-first-arg-unit-conversion [arg-unit unit-lower-bound ->ret-type ->call-code]
  (if (= arg-unit unit-lower-bound)
    {:return-type (->ret-type arg-unit),
     :->call-code #(->call-code arg-unit %)}

    (let [res-unit (types/smallest-ts-unit arg-unit unit-lower-bound)]
      {:return-type (->ret-type res-unit),
       :->call-code (fn [[arg1 & args]]
                      (->call-code res-unit (into [(with-conversion arg1 arg-unit res-unit)] args)))})))

(defn- wrap-handle-eot
  ([->call-code] (wrap-handle-eot ->call-code `Long/MAX_VALUE `Long/MAX_VALUE))
  ([->call-code src-eot-v tgt-eot-v]
   (let [v-sym (gensym 'v)]
     (fn [[v & args]]
       (let [inner (->call-code (into [v-sym] args))]
         `(let [~v-sym ~v]
            (if (= ~v-sym ~src-eot-v)
              ~tgt-eot-v
              ~inner)))))))

(defn- wrap-handle-eot2
  ([->call-code] (wrap-handle-eot2 ->call-code `Long/MAX_VALUE `Long/MAX_VALUE `Long/MAX_VALUE))
  ([->call-code left-eot-v right-eot-v tgt-eot-v]
   (let [left-sym (gensym 'left)
         right-sym (gensym 'right)]
     (fn [[left right & args]]
       (let [inner (->call-code (into [left-sym right-sym] args))]
         `(let [~left-sym ~left, ~right-sym ~right]
            (if (or (= ~left-sym ~left-eot-v) (= ~right-sym ~right-eot-v))
              ~tgt-eot-v
              ~inner)))))))

(defn- wrap-throw-eot2
  ([->call-code] (wrap-throw-eot2 ->call-code `Long/MAX_VALUE `Long/MAX_VALUE))
  ([->call-code left-eot-v right-eot-v]
   (let [left-sym (gensym 'left)
         right-sym (gensym 'right)]
     (fn [[left right & args]]
       (let [inner (->call-code (into [left-sym right-sym] args))]
         `(let [~left-sym ~left, ~right-sym ~right]
            (if (or (= ~left-sym ~left-eot-v) (= ~right-sym ~right-eot-v))
              (throw (RuntimeException. "cannot subtract infinite timestamps"))
              ~inner)))))))

(defn- time-unit->eot-v [time-unit]
  (case time-unit
    (:second :milli) Integer/MAX_VALUE
    (:micro :nano) Long/MAX_VALUE))

(defn- ts->inst [form ts-unit]
  (case ts-unit
    :second `(Instant/ofEpochSecond ~form)
    :milli `(Instant/ofEpochMilli ~form)
    :micro `(time/micros->instant ~form)
    :nano `(time/nanos->instant ~form)))

(defn- inst->ts [form ts-unit]
  (case ts-unit
    :second `(.getEpochSecond ~form)
    :milli `(.toEpochMilli ~form)
    :micro `(time/instant->micros ~form)
    :nano `(time/instant->nanos ~form)))

(defn- ts->zdt [form ts-unit tz-sym]
  `(ZonedDateTime/ofInstant ~(ts->inst form ts-unit) ~tz-sym))

(defn- zdt->ts [form ts-unit]
  (if (= ts-unit :second)
    `(.toEpochSecond ~form)
    `(let [form# ~form]
       (Math/addExact (Math/multiplyExact (long (.toEpochSecond form#)) ~(types/ts-units-per-second ts-unit))
                      (quot (long (.getNano form#)) ~(quot (types/ts-units-per-second :nano) (types/ts-units-per-second ts-unit)))))))

(defn- ldt->ts [form ts-unit]
  (if (= ts-unit :second)
    `(.toEpochSecond ~form ZoneOffset/UTC)
    `(let [form# ~form]
       (Math/addExact (Math/multiplyExact (long (.toEpochSecond form# ZoneOffset/UTC)) ~(types/ts-units-per-second ts-unit))
                      (quot (long (.getNano form#)) ~(quot (types/ts-units-per-second :nano) (types/ts-units-per-second ts-unit)))))))

(defn- ts->ldt [form ts-unit]
  `(let [form# ~form]
     (LocalDateTime/ofEpochSecond (quot form# ~(types/ts-units-per-second ts-unit))
                                  (* (mod form# ~(types/ts-units-per-second ts-unit))
                                     ~(quot (types/ts-units-per-second :nano) (types/ts-units-per-second ts-unit)))
                                  ZoneOffset/UTC)))

;;;; `CAST`

(defmethod expr/codegen-cast [:date :date] [{:keys [target-type]}]
  ;; date-days and date-millis are both just represented as days throughout the EE,
  ;; conversion is done when we read from/write to the vector.
  {:return-type target-type, :->call-code first})

(defmethod expr/codegen-cast [:time-local :time-local] [{[_ src-tsunit] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  {:return-type target-type,
   :->call-code (-> (comp #(with-conversion % src-tsunit tgt-tsunit) first)
                    (wrap-handle-eot (time-unit->eot-v src-tsunit) (time-unit->eot-v tgt-tsunit)))})

(defmethod expr/codegen-cast [:timestamp-local :timestamp-local] [{[_ src-tsunit] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  {:return-type target-type
   :->call-code (-> (comp #(with-conversion % src-tsunit tgt-tsunit) first)
                    (wrap-handle-eot))})

(defmethod expr/codegen-cast [:timestamp-tz :timestamp-tz] [{[_ src-tsunit _] :source-type, [_ tgt-tsunit _ :as target-type] :target-type}]
  {:return-type target-type
   :->call-code (-> (comp #(with-conversion % src-tsunit tgt-tsunit) first)
                    (wrap-handle-eot))})

(defmethod expr/codegen-cast [:duration :duration] [{[_ src-tsunit] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  {:return-type target-type, :->call-code (comp #(with-conversion % src-tsunit tgt-tsunit) first)})

(defmethod expr/codegen-cast [:date :timestamp-local] [{[_ tgt-tsunit :as target-type] :target-type}]
  {:return-type target-type
   :->call-code (-> (fn [[dt]]
                      `(-> (LocalDate/ofEpochDay ~dt)
                           (.atStartOfDay ZoneOffset/UTC)
                           (.toEpochSecond)
                           (Math/multiplyExact ~(types/ts-units-per-second tgt-tsunit))))
                    (wrap-handle-eot)
                    (wrap-handle-eot `~'Integer/MAX_VALUE `Long/MAX_VALUE))})

(defmethod expr/codegen-cast [:date :timestamp-tz] [{[_ tgt-tsunit _tgt-tz :as target-type] :target-type}]
  {:return-type target-type
   :->call-code (-> (fn [[dt]]
                      (-> `(-> (LocalDate/ofEpochDay ~dt)
                               (.atStartOfDay (.getZone expr/*clock*))
                               (.toInstant))
                          (inst->ts tgt-tsunit)))
                    (wrap-handle-eot `~'Integer/MAX_VALUE `Long/MAX_VALUE))})

(defmethod expr/codegen-cast [:time-local :timestamp-local] [{[_ src-tsunit] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  {:return-type target-type
   :->call-code (-> (fn [[tm]]
                      (-> `(LocalDateTime/of (LocalDate/ofInstant (.instant expr/*clock*) (.getZone expr/*clock*))
                                             (LocalTime/ofNanoOfDay ~(with-conversion tm src-tsunit :nano)))
                          (ldt->ts tgt-tsunit)))
                    (wrap-handle-eot (time-unit->eot-v src-tsunit) `Long/MAX_VALUE))})

(defmethod expr/codegen-cast [:time-local :timestamp-tz] [{[_ src-tsunit] :source-type, [_ tgt-tsunit _tgt-tz :as target-type] :target-type}]
  {:return-type target-type
   :->call-code (-> (fn [[tm]]
                      (-> `(-> (ZonedDateTime/of (LocalDate/ofInstant (.instant expr/*clock*) (.getZone expr/*clock*))
                                                 (LocalTime/ofNanoOfDay ~(with-conversion tm src-tsunit :nano))
                                                 (.getZone expr/*clock*))
                               (.toInstant))
                          (inst->ts tgt-tsunit)))
                    (wrap-handle-eot (time-unit->eot-v src-tsunit) `Long/MAX_VALUE))})

(defmethod expr/codegen-cast [:timestamp-local :date] [{[_ src-tsunit] :source-type, :keys [target-type]}]
  {:return-type target-type
   :->call-code (-> (fn [[ts]]
                      `(-> ~(ts->ldt ts src-tsunit)
                           (.toLocalDate)
                           (.toEpochDay)))
                    (wrap-handle-eot `Long/MAX_VALUE `~'Integer/MAX_VALUE))})

(defmethod expr/codegen-cast [:timestamp-local :time-local] [{[_ src-tsunit _] :source-type, [_ tgt-tsunit _ :as target-type] :target-type}]
  {:return-type target-type,
   :->call-code (-> (fn [[ts]]
                      (-> `(-> ~(ts->ldt ts src-tsunit)
                               (.toLocalTime)
                               (.toNanoOfDay))
                          (with-conversion :nano tgt-tsunit)))
                    (wrap-handle-eot `Long/MAX_VALUE (time-unit->eot-v tgt-tsunit)))})

(defmethod expr/codegen-cast [:timestamp-local :timestamp-tz] [{[_ src-tsunit] :source-type, [_ tgt-tsunit _tgt-tz :as target-type] :target-type}]
  {:return-type target-type,
   :->call-code (-> (fn [[ts]]
                      (-> `(-> ~(ts->ldt ts src-tsunit)
                               (.atZone (.getZone expr/*clock*))
                               (.toInstant))
                          (inst->ts tgt-tsunit)))
                    (wrap-handle-eot))})

(defmethod expr/codegen-cast [:timestamp-tz :date] [{[_ src-tsunit src-tz] :source-type, :keys [target-type]}]
  (let [src-tz-sym (gensym 'src-tz)]
    {:return-type target-type,
     :batch-bindings [[src-tz-sym `(ZoneId/of ~src-tz)]]
     :->call-code (-> (fn [[tstz]]
                        `(-> ~(ts->zdt tstz src-tsunit src-tz-sym)
                             (.withZoneSameInstant (.getZone expr/*clock*))
                             (.toLocalDate)
                             (.toEpochDay)))
                      (wrap-handle-eot `Long/MAX_VALUE `~'Integer/MAX_VALUE))}))

(defmethod expr/codegen-cast [:timestamp-tz :time-local] [{[_ src-tsunit src-tz] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  (let [src-tz-sym (gensym 'src-tz)]
    {:return-type target-type,
     :batch-bindings [[src-tz-sym `(ZoneId/of ~src-tz)]]
     :->call-code (-> (fn [[tstz]]
                        (-> `(-> ~(ts->zdt tstz src-tsunit src-tz-sym)
                                 (.withZoneSameInstant (.getZone expr/*clock*))
                                 (.toLocalTime)
                                 (.toNanoOfDay))
                            (with-conversion :nano tgt-tsunit)))
                      (wrap-handle-eot `Long/MAX_VALUE (time-unit->eot-v tgt-tsunit)))}))

(defmethod expr/codegen-cast [:timestamp-tz :timestamp-local] [{[_ src-tsunit src-tz] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  (let [src-tz-sym (gensym 'src-tz)]
    {:return-type target-type,
     :batch-bindings [[src-tz-sym `(ZoneId/of ~src-tz)]]
     :->call-code (-> (fn [[tstz]]
                        (-> `(-> ~(ts->zdt tstz src-tsunit src-tz-sym)
                                 (.withZoneSameInstant (.getZone expr/*clock*))
                                 (.toLocalDateTime))
                            (ldt->ts tgt-tsunit)))
                      (wrap-handle-eot))}))

(defmethod expr/codegen-cast [:time-local :duration] [{[_ src-tsunit] :source-type, [_ tgt-tsunit :as target-type] :target-type}]
  {:return-type target-type, :->call-code (comp #(with-conversion % src-tsunit tgt-tsunit) first)})

(defmethod expr/codegen-call [:cast_tstz :any] [expr]
  (expr/codegen-call (assoc expr :f :cast, :target-type [:timestamp-tz :micro (str (.getZone expr/*clock*))])))

(defmethod expr/codegen-cast [:interval :interval] [{:keys [source-type target-type]}]
  (assert (= source-type target-type) "TODO #478")
  {:return-type target-type, :->call-code first})

;;;; SQL:2011 Operations involving datetimes and intervals

(defn- recall-with-cast
  ([expr cast1 cast2] (recall-with-cast expr cast1 cast2 expr/codegen-call))

  ([{[t1 t2] :arg-types, :as expr} cast1 cast2 f]
   (let [{ret1 :return-type, bb1 :batch-bindings, ->cc1 :->call-code} (expr/codegen-cast {:source-type t1, :target-type cast1})
         {ret2 :return-type, bb2 :batch-bindings, ->cc2 :->call-code} (expr/codegen-cast {:source-type t2, :target-type cast2})
         {ret :return-type, bb :batch-bindings, ->cc :->call-code} (f (assoc expr :arg-types [ret1 ret2]))]
     {:return-type ret
      :batch-bindings (concat bb1 bb2 bb)
      :->call-code (fn [[a1 a2]]
                     (->cc [(->cc1 [a1]) (->cc2 [a2])]))})))

(defn- recall-with-flipped-args [expr]
  (let [{ret :return-type, bb :batch-bindings, ->cc :->call-code} (expr/codegen-call (update expr :arg-types (comp vec rseq)))]
    {:return-type ret, :batch-bindings bb, :->call-code (comp ->cc vec rseq)}))

;;; addition

(defmethod expr/codegen-call [:+ :date :time-local] [{[_ [_ time-unit :as arg2]] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:timestamp-local time-unit] arg2)))

(defmethod expr/codegen-call [:+ :timestamp-local :time-local] [{[[_ ts-unit], [_ time-unit]] :arg-types}]
  (-> (with-arg-unit-conversion ts-unit time-unit
        #(do [:timestamp-local %]) #(do `(Math/addExact ~@%)))
      (update :->call-code wrap-handle-eot2 `Long/MAX_VALUE (time-unit->eot-v time-unit) `Long/MAX_VALUE)))

(defmethod expr/codegen-call [:+ :timestamp-tz :time-local] [{[[_ ts-unit tz], [_time time-unit]] :arg-types}]
  (-> (with-arg-unit-conversion ts-unit time-unit
        #(do [:timestamp-tz % tz]) #(do `(Math/addExact ~@%)))
      (update :->call-code wrap-handle-eot2 `Long/MAX_VALUE (time-unit->eot-v time-unit) `Long/MAX_VLAUE)))

(defmethod expr/codegen-call [:+ :date :duration] [{[_ [_ dur-unit :as arg2]] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:timestamp-local dur-unit] arg2)))

(defmethod expr/codegen-call [:+ :timestamp-local :duration] [{[[_ ts-unit], [_ dur-unit]] :arg-types}]
  (-> (with-arg-unit-conversion ts-unit dur-unit
        #(do [:timestamp-local %]) #(do `(Math/addExact ~@%)))
      (update :->call-code wrap-handle-eot)))

(defmethod expr/codegen-call [:+ :timestamp-tz :duration] [{[[_ ts-unit tz], [_ dur-unit]] :arg-types}]
  (-> (with-arg-unit-conversion ts-unit dur-unit
        #(do [:timestamp-tz % tz]) #(do `(Math/addExact ~@%)))
      (update :->call-code wrap-handle-eot)))

(defmethod expr/codegen-call [:+ :duration :duration] [{[[_ x-unit] [_ y-unit]] :arg-types}]
  (with-arg-unit-conversion x-unit y-unit
    #(do [:duration %]) #(do `(Math/addExact ~@%))))

(doseq [[f-kw method-sym] [[:+ '.plus]
                           [:- '.minus]]]
  (defmethod expr/codegen-call [f-kw :date :interval] [{[dt-type, [_ iunit :as itype]] :arg-types, :as expr}]
    (case iunit
      :year-month {:return-type dt-type
                   :->call-code (-> (fn [[x-arg y-arg]]
                                      `(.toEpochDay (~method-sym (LocalDate/ofEpochDay ~x-arg) (.getPeriod ~y-arg))))
                                    (wrap-handle-eot `Integer/MAX_VALUE `Integer/MAX_VALUE))}
      :day-time (recall-with-cast expr [:timestamp-local :milli] itype)
      :month-day-nano (recall-with-cast expr [:timestamp-local :nano] itype)))

  (defmethod expr/codegen-call [f-kw :timestamp-local :interval] [{[[_ ts-unit :as ts-type], [_ iunit]] :arg-types}]
    (letfn [(codegen-call [unit-lower-bound]
              (with-first-arg-unit-conversion ts-unit unit-lower-bound
                #(do [:timestamp-local %])
                (fn [ts-unit [ts-arg i-arg]]
                  (-> `(let [i# ~i-arg]
                         (-> ~(ts->ldt ts-arg ts-unit)
                             (~method-sym (.getPeriod i#))
                             (~method-sym (.getDuration i#))))
                      (ldt->ts ts-unit)))))]
      (-> (case iunit
            :year-month {:return-type ts-type
                         :->call-code (fn [[x-arg y-arg]]
                                        (-> `(let [i# ~y-arg]
                                               (-> ~(ts->ldt x-arg ts-unit)
                                                   (~method-sym (.getPeriod i#))
                                                   (~method-sym (.getDuration i#))))
                                            (ldt->ts ts-unit)))}

            :day-time (codegen-call :milli)
            :month-day-nano (codegen-call :nano))
          (update :->call-code wrap-handle-eot))))

  (defmethod expr/codegen-call [f-kw :timestamp-tz :interval] [{[[_ ts-unit tz :as ts-type], [_ iunit]] :arg-types}]
    (let [zone-id-sym (gensym 'zone-id)]
      (letfn [(codegen-call [unit-lower-bound]
                (with-first-arg-unit-conversion ts-unit unit-lower-bound
                  #(do [:timestamp-tz % tz])
                  (fn [ts-unit [ts-arg i-arg]]
                    (-> `(let [i# ~i-arg]
                           (-> ~(ts->zdt ts-arg ts-unit zone-id-sym)
                               (~method-sym (.getPeriod i#))
                               (~method-sym (.getDuration i#))))
                        (zdt->ts ts-unit)))))]
        (-> (case iunit
              :year-month {:return-type ts-type
                           :->call-code (fn [[x-arg y-arg]]
                                          (-> `(let [y# ~y-arg]
                                                 (-> ~(ts->zdt x-arg ts-unit zone-id-sym)
                                                     (~method-sym (.getPeriod y#))
                                                     (~method-sym (.getDuration y#))))
                                              (zdt->ts ts-unit)))}

              :day-time (codegen-call :milli)
              :month-day-nano (codegen-call :nano))
            (update :batch-bindings (fnil conj []) [zone-id-sym `(ZoneId/of ~(str tz))])
            (update :->call-code wrap-handle-eot))))))

(doseq [[t1 t2] [[:duration :timestamp-tz]
                 [:duration :timestamp-local]
                 [:duration :date]
                 [:time-local :date]
                 [:time-local :timestamp-local]
                 [:time-local :timestamp-tz]
                 [:interval :date]
                 [:interval :timestamp-local]
                 [:interval :timestamp-tz]]]
  (defmethod expr/codegen-call [:+ t1 t2] [expr]
    (recall-with-flipped-args expr)))

;;; subtract

(defmethod expr/codegen-call [:- :timestamp-tz :timestamp-tz] [{[[_ x-unit _], [_ y-unit _]] :arg-types}]
  (-> (with-arg-unit-conversion x-unit y-unit
        #(do [:duration %]) #(do `(Math/subtractExact ~@%)))
      (update :->call-code wrap-throw-eot2 `Long/MAX_VALUE `Long/MAX_VALUE)))

(defmethod expr/codegen-call [:- :timestamp-local :timestamp-local] [{[[_ x-unit], [_ y-unit]] :arg-types}]
  (-> (with-arg-unit-conversion x-unit y-unit
        #(do [:duration %]) #(do `(Math/subtractExact ~@%)))
      (update :->call-code wrap-throw-eot2 `Long/MAX_VALUE `Long/MAX_VALUE)))

(defmethod expr/codegen-call [:- :timestamp-local :timestamp-tz] [{[x [_ y-unit _]] :arg-types, :as expr}]
  (-> expr (recall-with-cast x [:timestamp-local y-unit])))

(defmethod expr/codegen-call [:- :timestamp-tz :timestamp-local] [{[[_ x-unit] y] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:timestamp-local x-unit] y)))

(defmethod expr/codegen-call [:- :date :date] [expr]
  (-> expr (recall-with-cast [:timestamp-local :micro] [:timestamp-local :micro])))

(doseq [t [:timestamp-tz :timestamp-local]]
  (defmethod expr/codegen-call [:- :date t] [{[_ t2] :arg-types, :as expr}]
    (-> expr (recall-with-cast [:timestamp-local :micro] t2)))

  (defmethod expr/codegen-call [:- t :date] [{[t1 _] :arg-types, :as expr}]
    (-> expr (recall-with-cast t1 [:timestamp-local :micro]))))

(defmethod expr/codegen-call [:- :time-local :time-local] [{[[_ time-unit1] [_ time-unit2]] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:duration time-unit1] [:duration time-unit2])))

(doseq [th [:date :timestamp-tz :timestamp-local]]
  (defmethod expr/codegen-call [:- th :time-local] [{[t [_ time-unit]] :arg-types, :as expr}]
    (-> expr (recall-with-cast t [:duration time-unit]))))

(defmethod expr/codegen-call [:- :timestamp-tz :duration] [{[[_ts ts-unit tz], [_dur dur-unit]] :arg-types}]
  (-> (with-arg-unit-conversion ts-unit dur-unit
        #(do [:timestamp-tz % tz]) #(do `(Math/subtractExact ~@%)))
      (update :->call-code wrap-handle-eot)))

(defmethod expr/codegen-call [:- :date :duration] [{[_ [_dur dur-unit :as arg2]] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:timestamp-local dur-unit] arg2)))

(defmethod expr/codegen-call [:- :timestamp-local :duration] [{[[_ts ts-unit], [_dur dur-unit]] :arg-types}]
  (-> (with-arg-unit-conversion ts-unit dur-unit
        #(do [:timestamp-local %]) #(do `(Math/subtractExact ~@%)))
      (update :->call-code wrap-handle-eot)))

(defmethod expr/codegen-call [:- :duration :duration] [{[[_x x-unit] [_y y-unit]] :arg-types}]
  (with-arg-unit-conversion x-unit y-unit
    #(do [:duration %]) #(do `(Math/subtractExact ~@%))))

(defmethod expr/codegen-call [:- :date :interval] [{[dt-type, [_ iunit :as itype]] :arg-types, :as expr}]
  (case iunit
    :year-month {:return-type dt-type
                 :->call-code (-> (fn [[x-arg y-arg]]
                                    `(.toEpochDay (.minus (LocalDate/ofEpochDay ~x-arg) (.getPeriod ~y-arg))))
                                  (wrap-handle-eot `Integer/MAX_VALUE `Integer/MAX_VALUE))}
    :day-time (recall-with-cast expr [:timestamp-local :milli] itype)
    :month-day-nano (recall-with-cast expr [:timestamp-local :nano] itype)))

;;; multiply, divide

(defmethod expr/codegen-call [:* :duration :int] [{[x-type _y-type] :arg-types}]
  {:return-type x-type
   :->call-code (fn [emitted-args]
                  `(Math/multiplyExact ~@emitted-args))})

(defmethod expr/codegen-call [:* :duration :num] [{[x-type _y-type] :arg-types}]
  {:return-type x-type
   :->call-code (fn [emitted-args]
                  `(* ~@emitted-args))})

(defmethod expr/codegen-call [:* :int :duration] [{[_x-type y-type] :arg-types}]
  {:return-type y-type
   :->call-code (fn [emitted-args]
                  `(Math/multiplyExact ~@emitted-args))})

(defmethod expr/codegen-call [:* :num :duration] [{[_x-type y-type] :arg-types}]
  {:return-type y-type
   :->call-code (fn [emitted-args]
                  `(long (* ~@emitted-args)))})

(defmethod expr/codegen-call [:/ :duration :num] [{[x-type] :arg-types}]
  {:return-type x-type
   :->call-code (fn [emitted-args]
                  `(quot ~@emitted-args))})

;;;; Boolean operations

(defmethod expr/codegen-call [:compare :timestamp-tz :timestamp-tz] [{[[_ x-unit _], [_ y-unit _]] :arg-types}]
  (-> (with-arg-unit-conversion x-unit y-unit
        (constantly :i32) #(do `(Long/compare ~@%)))
      (update :compare>call-code wrap-throw-eot2 `Long/MAX_VALUE `Long/MAX_VALUE)))

(defmethod expr/codegen-call [:compare :timestamp-local :timestamp-local] [{[[_ x-unit], [_ y-unit]] :arg-types}]
  (-> (with-arg-unit-conversion x-unit y-unit
        (constantly :i32) #(do `(Long/compare ~@%)))
      (update :compare>call-code wrap-throw-eot2 `Long/MAX_VALUE `Long/MAX_VALUE)))

(defmethod expr/codegen-call [:compare :timestamp-local :timestamp-tz] [{[x [_ y-unit _]] :arg-types, :as expr}]
  (-> expr (recall-with-cast x [:timestamp-local y-unit])))

(defmethod expr/codegen-call [:compare :timestamp-tz :timestamp-local] [{[[_ x-unit] y] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:timestamp-local x-unit] y)))

(defmethod expr/codegen-call [:compare :date :date] [expr]
  (-> expr (recall-with-cast [:timestamp-local :micro] [:timestamp-local :micro])))

(doseq [t [:timestamp-tz :timestamp-local]]
  (defmethod expr/codegen-call [:compare :date t] [{[_ t2] :arg-types, :as expr}]
    (-> expr (recall-with-cast [:timestamp-local :micro] t2)))

  (defmethod expr/codegen-call [:compare t :date] [{[t1 _] :arg-types, :as expr}]
    (-> expr (recall-with-cast t1 [:timestamp-local :micro]))))

(defmethod expr/codegen-call [:compare :time-local :time-local] [{[[_ time-unit1] [_ time-unit2]] :arg-types, :as expr}]
  (-> expr (recall-with-cast [:duration time-unit1] [:duration time-unit2])))

(defmethod expr/codegen-call [:compare :duration :duration] [{[[_x x-unit] [_y y-unit]] :arg-types}]
  (with-arg-unit-conversion x-unit y-unit
    (constantly :i32) #(do `(Long/compare ~@%))))

(doseq [[f cmp] [[:= #(do `(zero? ~%))]
                 [:< #(do `(neg? ~%))]
                 [:<= #(do `(not (pos? ~%)))]
                 [:> #(do `(pos? ~%))]
                 [:>= #(do `(not (neg? ~%)))]]]
  (doseq [x [:date :timestamp-local :timestamp-tz]
          y [:date :timestamp-local :timestamp-tz]]
    (defmethod expr/codegen-call [f x y] [expr]
      (let [{:keys [batch-bindings ->call-code]} (expr/codegen-call (assoc expr :f :compare))]
        {:return-type :bool,
         :batch-bindings batch-bindings
         :->call-code (comp cmp ->call-code)})))

  (doseq [x [:time-local :duration]]
    (defmethod expr/codegen-call [f x x] [expr]
      (let [{:keys [batch-bindings ->call-code]} (expr/codegen-call (assoc expr :f :compare))]
        {:return-type :bool,
         :batch-bindings batch-bindings
         :->call-code (comp cmp ->call-code)}))))

;;;; Intervals

(defn pd-add ^PeriodDuration [^PeriodDuration pd1 ^PeriodDuration pd2]
  (let [p1 (.getPeriod pd1)
        p2 (.getPeriod pd2)
        d1 (.getDuration pd1)
        d2 (.getDuration pd2)]
    (PeriodDuration. (.plus p1 p2) (.plus d1 d2))))

(defn pd-sub ^PeriodDuration [^PeriodDuration pd1 ^PeriodDuration pd2]
  (let [p1 (.getPeriod pd1)
        p2 (.getPeriod pd2)
        d1 (.getDuration pd1)
        d2 (.getDuration pd2)]
    (PeriodDuration. (.minus p1 p2) (.minus d1 d2))))

(defn pd-neg ^PeriodDuration [^PeriodDuration pd]
  (PeriodDuration. (.negated (.getPeriod pd)) (.negated (.getDuration pd))))

(defn- choose-interval-arith-return
  "Given two interval types, return an interval type that can represent the result of an binary arithmetic expression
  over those types, i.e + or -.

  If you add two YearMonth intervals, you can use an YearMonth representation for the result, if you add a YearMonth
  and a MonthDayNano, you must use MonthDayNano to represent the result."
  [[_interval l-unit] [_interval r-unit]]
  (if (= l-unit r-unit)
    l-unit
    ;; we could be smarter about the return type here to allow a more compact representation
    ;; for day time cases
    :month-day-nano))

(defmethod expr/codegen-call [:+ :interval :interval] [{[l-type r-type] :arg-types}]
  (let [return-type (choose-interval-arith-return l-type r-type)]
    {:return-type [:interval return-type]
     :->call-code (fn [[l r]] `(pd-add ~l ~r))}))

(defmethod expr/codegen-call [:- :interval :interval] [{[l-type r-type] :arg-types}]
  (let [return-type (choose-interval-arith-return l-type r-type)]
    {:return-type [:interval return-type]
     :->call-code (fn [[l r]] `(pd-sub ~l ~r))}))

(defmethod expr/codegen-call [:- :interval] [{[interval-type] :arg-types}]
  {:return-type interval-type
   :->call-code (fn [[i]] `(pd-neg ~i))})

(defn pd-scale ^PeriodDuration [^PeriodDuration pd ^long factor]
  (let [p (.getPeriod pd)
        d (.getDuration pd)]
    (PeriodDuration. (.multipliedBy p factor) (.multipliedBy d factor))))

(defmethod expr/codegen-call [:* :interval :int] [{[l-type _] :arg-types}]
  {:return-type l-type
   :->call-code (fn [[a b]] `(pd-scale ~a ~b))})

(defmethod expr/codegen-call [:* :int :interval] [{[_ r-type] :arg-types}]
  {:return-type r-type
   :->call-code (fn [[a b]] `(pd-scale ~b ~a))})

;; numeric division for intervals
;; can only be supported for Year_Month and Day_Time interval units without
;; ambguity, e.g if I was to divide an interval containing P1M20D, I could return P10D for the day component, but I have
;; to truncate the month component to zero (as it is not divisible into days).

(defn pd-year-month-div ^PeriodDuration [^PeriodDuration pd ^long divisor]
  (let [p (.getPeriod pd)]
    (PeriodDuration.
      (Period/of 0 (quot (.toTotalMonths p) divisor) 0)
      Duration/ZERO)))

(defn pd-day-time-div ^PeriodDuration [^PeriodDuration pd ^long divisor]
  (let [p (.getPeriod pd)
        d (.getDuration pd)]
    (PeriodDuration.
      (Period/ofDays (quot (.getDays p) divisor))
      (Duration/ofSeconds (quot (.toSeconds d) divisor)))))

(defmethod expr/codegen-call [:/ :interval :int] [{[[_interval iunit] _]:arg-types}]
  (case iunit
    :year-month {:return-type [:interval :year-month]
                 :->call-code (fn [[a b]] `(pd-year-month-div ~a ~b))}
    :day-time {:return-type [:interval :day-time]
               :->call-code (fn [[a b]] `(pd-day-time-div ~a ~b))}
    (throw (UnsupportedOperationException. "Cannot divide mixed period / duration intervals"))))

(defn interval-abs-ym
  "In SQL the ABS function can be applied to intervals, negating them if they are below some definition of 'zero' for the components
  of the intervals.

  We only support abs on YEAR_MONTH and DAY_TIME typed vectors at the moment, This seems compliant with the standard
  which only talks about ABS being applied to a single component interval.

  For YEAR_MONTH, we define where ZERO as 0 months.
  For DAY_TIME we define ZERO as 0 seconds (interval-abs-dt)."
  ^PeriodDuration [^PeriodDuration pd]
  (let [p (.getPeriod pd)]
    (if (<= 0 (.toTotalMonths p))
      pd
      (PeriodDuration. (Period/ofMonths (- (.toTotalMonths p))) Duration/ZERO))))

(defn interval-abs-dt ^PeriodDuration [^PeriodDuration pd]
  (let [p (.getPeriod pd)
        d (.getDuration pd)

        days (.getDays p)
        secs (.toSeconds d)]
    ;; cast to long to avoid overflow during calc
    (if (<= 0 (+ (long secs) (* (long days) 24 60 60)))
      pd
      (PeriodDuration. (Period/ofDays (- days)) (Duration/ofSeconds (- secs))))))

(defmethod expr/codegen-call [:abs :interval] [{[[_interval iunit :as itype]] :arg-types}]
  {:return-type itype,
   :->call-code (case iunit
                  :year-month #(do `(interval-abs-ym ~@%))
                  :day-time #(do `(interval-abs-dt ~@%))
                  (throw (UnsupportedOperationException. "Can only ABS YEAR_MONTH / DAY_TIME intervals")))})

(defn interval-eq
  "Override equality for intervals as we want 1 year to = 12 months, and this is not true by for Period.equals."
  ;; we might be able to enforce this differently (e.g all constructs, reads and calcs only use the month component).
  [^PeriodDuration pd1 ^PeriodDuration pd2]
  (let [p1 (.getPeriod pd1)
        p2 (.getPeriod pd2)]
    (and (= (.toTotalMonths p1) (.toTotalMonths p2))
         (= (.getDays p1) (.getDays p2))
         (= (.getDuration pd1) (.getDuration pd2)))))

;; interval equality specialisation, see interval-eq.
(defmethod expr/codegen-call [:= :interval :interval] [_]
  {:return-type :bool, :->call-code #(do `(boolean (interval-eq ~@%)))})

(defn- ensure-interval-precision-valid [^long precision]
  (cond
    (< precision 1)
    (throw (err/illegal-arg :xtdb.expression/invalid-interval-precision
                            {::err/message "The minimum leading field precision is 1."
                             :precision precision}))

    (< 8 precision)
    (throw (err/illegal-arg :xtdb.expression/invalid-interval-precision
                            {::err/message "The maximum leading field precision is 8."
                             :precision precision}))))

(defn- ensure-interval-fractional-precision-valid [^long fractional-precision]
  (cond
    (< fractional-precision 0)
    (throw (err/illegal-arg :xtdb.expression/invalid-interval-fractional-precision
                            {::err/message "The minimum fractional seconds precision is 0."
                             :fractional-precision fractional-precision}))

    (< 9 fractional-precision)
    (throw (err/illegal-arg :xtdb.expression/invalid-interval-fractional-precision
                            {::err/message "The maximum fractional seconds precision is 9."
                             :fractional-precision fractional-precision}))))

(defmethod expr/codegen-call [:single_field_interval :int :utf8 :int :int] [{:keys [args]}]
  (let [[_ unit precision fractional-precision] (map :literal args)]
    (ensure-interval-precision-valid precision)
    (when (= "SECOND" unit)
      (ensure-interval-fractional-precision-valid fractional-precision))

    (case unit
      "YEAR" {:return-type [:interval :year-month]
              :->call-code #(do `(PeriodDuration. (Period/ofYears ~(first %)) Duration/ZERO))}
      "MONTH" {:return-type [:interval :year-month]
               :->call-code #(do `(PeriodDuration. (Period/ofMonths ~(first %)) Duration/ZERO))}
      "DAY" {:return-type [:interval :day-time]
             :->call-code #(do `(PeriodDuration. (Period/ofDays ~(first %)) Duration/ZERO))}
      "HOUR" {:return-type [:interval :day-time]
              :->call-code #(do `(PeriodDuration. Period/ZERO (Duration/ofHours ~(first %))))}
      "MINUTE" {:return-type [:interval :day-time]
                :->call-code #(do `(PeriodDuration. Period/ZERO (Duration/ofMinutes ~(first %))))}
      "SECOND" {:return-type [:interval :day-time]
                :->call-code #(do `(PeriodDuration. Period/ZERO (Duration/ofSeconds ~(first %))))})))

(defn ensure-single-field-interval-int
  "Takes a string or UTF8 ByteBuffer and returns an integer, throws a parse error if the string does not contain an integer.

  This is used to parse INTERVAL literal strings, e.g INTERVAL '3' DAY, as the grammar has been overriden to emit a plain string."
  [string-or-buf]
  (let [interval-str (expr/resolve-string string-or-buf)]
    (try
      (Integer/valueOf interval-str)
      (catch NumberFormatException _
        (throw (err/illegal-arg :xtdb.expression/invalid-interval
                                {::err/message "Parse error. Single field INTERVAL string must contain a positive or negative integer."
                                 :interval interval-str}))))))

(defn second-interval-fractional-duration
  "Takes a string or UTF8 ByteBuffer and returns Duration for a fractional seconds INTERVAL literal.

  e.g INTERVAL '3.14' SECOND

  Throws a parse error if the string does not contain an integer / decimal. Throws on overflow."
  ^Duration [string-or-buf]
  (let [interval-str (expr/resolve-string string-or-buf)]
    (try
      (let [bd (bigdec interval-str)
            ;; will throw on overflow, is a custom error message needed?
            secs (.setScale bd 0 BigDecimal/ROUND_DOWN)
            nanos (.longValueExact (.setScale (.multiply (.subtract bd secs) 1e9M) 0 BigDecimal/ROUND_DOWN))]
        (Duration/ofSeconds (.longValueExact secs) nanos))
      (catch NumberFormatException _
        (throw (err/illegal-arg :xtdb.expression/invalid-interval
                                {::err/message "Parse error. SECOND INTERVAL string must contain a positive or negative integer or decimal."
                                 :interval interval-str}))))))

(defmethod expr/codegen-call [:single_field_interval :utf8 :utf8 :int :int] [{:keys [args]}]
  (let [[_ unit precision fractional-precision] (map :literal args)]
    (ensure-interval-precision-valid precision)
    (when (= "SECOND" unit)
      (ensure-interval-fractional-precision-valid fractional-precision))

    (case unit
      "YEAR" {:return-type [:interval :year-month]
              :->call-code #(do `(PeriodDuration. (Period/ofYears (ensure-single-field-interval-int ~(first %))) Duration/ZERO))}
      "MONTH" {:return-type [:interval :year-month]
               :->call-code #(do `(PeriodDuration. (Period/ofMonths (ensure-single-field-interval-int ~(first %))) Duration/ZERO))}
      "DAY" {:return-type [:interval :day-time]
             :->call-code #(do `(PeriodDuration. (Period/ofDays (ensure-single-field-interval-int ~(first %))) Duration/ZERO))}
      "HOUR" {:return-type [:interval :day-time]
              :->call-code #(do `(PeriodDuration. Period/ZERO (Duration/ofHours (ensure-single-field-interval-int ~(first %)))))}
      "MINUTE" {:return-type [:interval :day-time]
                :->call-code #(do `(PeriodDuration. Period/ZERO (Duration/ofMinutes (ensure-single-field-interval-int ~(first %)))))}
      "SECOND" {:return-type [:interval :day-time]
                :->call-code #(do `(PeriodDuration. Period/ZERO (second-interval-fractional-duration ~(first %))))})))

(defn- parse-year-month-literal [s]
  (let [[match plus-minus part1 part2] (re-find #"^([-+]|)(\d+)\-(\d+)" s)]
    (when match
      (let [months (+ (* 12 (Integer/parseInt part1)) (Integer/parseInt part2))
            months' (if (= plus-minus "-") (- months) months)]
        (PeriodDuration. (Period/ofMonths months') Duration/ZERO)))))

(defn- fractional-secs-to->nanos ^long [fractional-secs]
  (if fractional-secs
    (let [num-digits (if (str/starts-with? fractional-secs "-")
                       (unchecked-dec-int (count fractional-secs))
                       (count fractional-secs))
          exp (- 9 num-digits)]
      (* (Math/pow 10 exp) (Long/parseLong fractional-secs)))
    0))

(defn- parse-day-to-second-literal [s unit1 unit2]
  (letfn [(negate-if-minus [plus-minus ^PeriodDuration pd]
            (if (= "-" plus-minus)
              (PeriodDuration.
               (.negated (.getPeriod pd))
               (.negated (.getDuration pd)))
              pd))]
    (case [unit1 unit2]
      ["DAY" "SECOND"]
      (let [re #"^([-+]|)(\d+) (\d+)\:(\d+):(\d+)(\.(\d+)){0,1}$"
            [match plus-minus day hour min sec _ fractional-secs] (re-find re s)]
        (when match
          (negate-if-minus
           plus-minus
           (PeriodDuration. (Period/of 0 0 (Integer/parseInt day))
                            (Duration/ofSeconds (+ (* 60 60 (Integer/parseInt hour))
                                                   (* 60 (Integer/parseInt min))
                                                   (Integer/parseInt sec))
                                                (fractional-secs-to->nanos fractional-secs))))))
      ["DAY" "MINUTE"]
      (let [re #"^([-+]|)(\d+) (\d+)\:(\d+)$"
            [match plus-minus day hour min] (re-find re s)]
        (when match
          (negate-if-minus
           plus-minus
           (PeriodDuration. (Period/of 0 0 (Integer/parseInt day))
                            (Duration/ofMinutes (+ (* 60 (Integer/parseInt hour))
                                                   (Integer/parseInt min)))))))

      ["DAY" "HOUR"]
      (let [re #"^([-+]|)(\d+) (\d+)$"
            [match plus-minus day hour] (re-find re s)]
        (when match
          (negate-if-minus
           plus-minus
           (PeriodDuration. (Period/of 0 0 (Integer/parseInt day))
                            (Duration/ofHours (Integer/parseInt hour))))))

      ["HOUR" "SECOND"]
      (let [re #"^([-+]|)(\d+)\:(\d+):(\d+)(\.(\d+)){0,1}$"
            [match plus-minus hour min sec _ fractional-secs] (re-find re s)]
        (when match
          (negate-if-minus
           plus-minus
           (PeriodDuration. Period/ZERO
                            (Duration/ofSeconds (+ (* 60 60 (Integer/parseInt hour))
                                                   (* 60 (Integer/parseInt min))
                                                   (Integer/parseInt sec))
                                                (fractional-secs-to->nanos fractional-secs))))))

      ["HOUR" "MINUTE"]
      (let [re #"^([-+]|)(\d+)\:(\d+)$"
            [match plus-minus hour min] (re-find re s)]
        (when match
          (negate-if-minus
           plus-minus
           (PeriodDuration. Period/ZERO
                            (Duration/ofMinutes (+ (* 60 (Integer/parseInt hour))
                                                   (Integer/parseInt min)))))))

      ["MINUTE" "SECOND"]
      (let [re #"^([-+]|)(\d+):(\d+)(\.(\d+)){0,1}$"
            [match plus-minus min sec _ fractional-secs] (re-find re s)]
        (when match
          (negate-if-minus
           plus-minus
           (PeriodDuration. Period/ZERO
                            (Duration/ofSeconds (+ (* 60 (Integer/parseInt min))
                                                   (Integer/parseInt sec))
                                                (fractional-secs-to->nanos fractional-secs)))))))))

(defn parse-multi-field-interval
  "This function is used to parse a 2 field interval literal into a PeriodDuration, e.g '12-03' YEAR TO MONTH."
  ^PeriodDuration [s unit1 unit2]
  ;; This function overwhelming likely to be applied as a const-expr so not concerned about vectorized perf.
  ;; these rules are not strictly necessary but are specified by SQL2011

  (letfn [(->iae [msg]
            (err/illegal-arg :xtdb.expression/invalid-interval-units
                             {::err/message msg
                              :start-unit unit1
                              :end-unit unit2}))]
    (when (and (= unit1 "YEAR") (not= unit2 "MONTH"))
      (throw (->iae "If YEAR specified as the interval start field, MONTH must be the end field.")))

    (when (= unit1 "MONTH")
      (throw (->iae "MONTH is not permitted as the interval start field.")))

    ;; less significance rule.
    (when-not (or (= unit1 "YEAR")
                  (and (= unit1 "DAY") (#{"HOUR" "MINUTE" "SECOND"} unit2))
                  (and (= unit1 "HOUR") (#{"MINUTE" "SECOND"} unit2))
                  (and (= unit1 "MINUTE") (#{"SECOND"} unit2)))
      (throw (->iae "Interval end field must have less significance than the start field.")))

    (or (if (= "YEAR" unit1)
          (parse-year-month-literal s)
          (parse-day-to-second-literal s unit1 unit2))
        (throw (->iae "Cannot parse interval, incorrect format.")))))

(defmethod expr/codegen-call [:multi_field_interval :utf8 :utf8 :int :utf8 :int] [{:keys [args]}]
  (let [[_ unit1 precision unit2 fractional-precision] (map :literal args)]

    (ensure-interval-precision-valid precision)
    (when (= "SECOND" unit2)
      (ensure-interval-fractional-precision-valid fractional-precision))

    ;; TODO choose a more specific representation when possible
    {:return-type (case [unit1 unit2]
                    ["YEAR" "MONTH"] [:interval :year-month]
                    [:interval :month-day-nano])
     :->call-code (fn [[s & _]]
                    `(parse-multi-field-interval (expr/resolve-string ~s) ~unit1 ~unit2))}))


(defn time-field->ChronoField
  [field]
  (case field
    "YEAR" `ChronoField/YEAR
    "MONTH" `ChronoField/MONTH_OF_YEAR
    "DAY" `ChronoField/DAY_OF_MONTH
    "HOUR" `ChronoField/HOUR_OF_DAY
    "MINUTE" `ChronoField/MINUTE_OF_HOUR
    "SECOND" `ChronoField/SECOND_OF_MINUTE)) 

(defmethod expr/codegen-call [:extract :utf8 :timestamp-tz] [{[{field :literal} _] :args, [_ [_ts ts-unit tz]] :arg-types}]
  (let [zone-id-sym (gensym 'zone-id)]
    {:return-type :i32
     :batch-bindings [[zone-id-sym (ZoneId/of tz)]]
     :->call-code (fn [[_ x]]
                    `(-> ~(ts->zdt x ts-unit zone-id-sym)
                         ~(case field
                            "TIMEZONE_HOUR" `(-> (.getOffset) (.getTotalSeconds) (/ 3600) (int))
                            "TIMEZONE_MINUTE" `(-> (.getOffset) (.getTotalSeconds) (/ 60) (rem 60) (int))
                            `(.get ~(time-field->ChronoField field)))))}))

(defmethod expr/codegen-call [:extract :utf8 :timestamp-local] [{[{field :literal} _] :args, [_ [_ts ts-unit]] :arg-types}]
  {:return-type :i32
   :->call-code (fn [[_ x]]
                  `(-> ~(ts->ldt x ts-unit)
                       ~(case field
                          "TIMEZONE_HOUR" (throw (UnsupportedOperationException. "Extract \"TIMEZONE_HOUR\" not supported for type timestamp without timezone"))
                          "TIMEZONE_MINUTE" (throw (UnsupportedOperationException. "Extract \"TIMEZONE_MINUTE\" not supported for type timestamp without timezone"))
                          `(.get ~(time-field->ChronoField field)))))})

(defmethod expr/codegen-call [:extract :utf8 :date] [{[{field :literal} _] :args}]
  ;; FIXME this assumes date-unit :day
  {:return-type :i32
   :->call-code (fn [[_ epoch-day-code]]
                  (case field
                    "YEAR" `(.getYear (LocalDate/ofEpochDay ~epoch-day-code))
                    "MONTH" `(.getMonthValue (LocalDate/ofEpochDay ~epoch-day-code))
                    "DAY" `(.getDayOfMonth (LocalDate/ofEpochDay ~epoch-day-code))
                    (throw (UnsupportedOperationException. (format "Extract \"%s\" not supported for type date" field)))))})

(defmethod expr/codegen-call [:extract :utf8 :interval] [{[{field :literal} _] :args}]
  {:return-type :i32
   :->call-code (fn [[_ pd]]
                  (let [period `(.getPeriod ^PeriodDuration ~pd)
                        duration `(.getDuration ^PeriodDuration ~pd)]
                    (case field
                      "YEAR" `(-> (.toTotalMonths ~period) (/ 12) (int))
                      "MONTH" `(-> (.toTotalMonths ~period) (rem 12) (int))
                      "DAY" `(.getDays ~period)
                      "HOUR" `(-> (.toHours ~duration) (int))
                      "MINUTE" `(-> (.toMinutes ~duration) (rem 60) (int))
                      "SECOND" `(-> (.toSeconds ~duration) (rem 60) (int))
                      (throw (UnsupportedOperationException. (format "Extract \"%s\" not supported for type interval" field))))))})

(defn field->truncate-fn
  [field]
  (case field
     "MILLENNIUM" `(DateTruncator/truncateYear 1000)
     "CENTURY" `(DateTruncator/truncateYear 100)
     "DECADE" `(DateTruncator/truncateYear 10)
     "YEAR" `(DateTruncator/truncateYear)
     "QUARTER" `(DateTruncator/truncateQuarter)
     "MONTH" `(DateTruncator/truncateMonth)
     "WEEK" `(DateTruncator/truncateWeek)
     `(.truncatedTo ~(case field
                       "DAY" `ChronoUnit/DAYS
                       "HOUR" `ChronoUnit/HOURS
                       "MINUTE" `ChronoUnit/MINUTES
                       "SECOND" `ChronoUnit/SECONDS
                       "MILLISECOND" `ChronoUnit/MILLIS
                       "MICROSECOND" `ChronoUnit/MICROS))))

;; 1. We pass in the arrow timestamp - essentially a Long with some units (ts-unit) and a timezone (tz)
;; 2. Convert the tz to a ZoneId
;; 3. We take the long and the units, and use these to create an Instant
;; 4. We convert the instant to a ZonedDateTime with the ZoneId
;; 5. We truncate the ZonedDateTime, to the equivalent `field` value
;; 6. We convert the ZonedDateTime back to a Long with the same units as the input
;; 7. The generated code returns the Long.
(defmethod expr/codegen-call [:date_trunc :utf8 :timestamp-tz] [{[{field :literal} _] :args, [_ [_tstz ts-unit tz :as ts-type]] :arg-types}]
  (let [zone-id-sym (gensym 'zone-id)]
    {:return-type ts-type
     :batch-bindings [[zone-id-sym (ZoneId/of tz)]]
     :->call-code (fn [[_ x]]
                    (-> `(-> ~(ts->zdt x ts-unit zone-id-sym)
                             ~(field->truncate-fn field))
                        (zdt->ts ts-unit)))}))

(defmethod expr/codegen-call [:date_trunc :utf8 :timestamp-local] [{[{field :literal} _] :args, [_ [_ts ts-unit :as ts-type]] :arg-types}]
    {:return-type ts-type
     :->call-code (fn [[_ x]]
                    (-> `(-> ~(ts->ldt x ts-unit)
                             ~(field->truncate-fn field))
                        (ldt->ts ts-unit)))})

;; 1. We pass in the arrow timestamp - essentially a Long with some units (ts-unit) and a timezone (tz), and we have a provided time_zone argument
;; 2. Convert the time_zone to ZoneId
;; 3. We take the long and the units, and use these to create an Instant
;; 4. We convert the instant to a ZonedDateTime with the time_zone ZoneId
;; 5. We truncate the ZonedDateTime, to the equivalent `field` value
;; 6. We convert the ZonedDateTime back to a Long with the same units as the input
;; 7. The generated code returns the Long, with the original tz from the args.
(defmethod expr/codegen-call [:date_trunc :utf8 :timestamp-tz :utf8] [{[{field :literal} _ {trunc-tz :literal}] :args, [_ [_tstz ts-unit _tz :as ts-type] _] :arg-types}]
  (let [trunc-zone-id-sym (gensym 'zone-id)]
    {:return-type ts-type
     :batch-bindings [[trunc-zone-id-sym (ZoneId/of trunc-tz)]]
     :->call-code (fn [[_ x]]
                    (-> `(-> ~(ts->zdt x ts-unit trunc-zone-id-sym)
                             ~(field->truncate-fn field))
                        (zdt->ts ts-unit)))}))

(defmethod expr/codegen-call [:date_trunc :utf8 :date] [{[{field :literal} _] :args, [_ [_date _date-unit :as date-type]] :arg-types}]
  ;; FIXME this assumes epoch-day
  {:return-type date-type
   :->call-code (fn [[_ epoch-day-code]]
                  `(-> (LocalDate/ofEpochDay ~epoch-day-code)
                       ~(case field
                         "MILLENNIUM" `(DateTruncator/truncateYear 1000)
                         "CENTURY" `(DateTruncator/truncateYear 100)
                         "DECADE" `(DateTruncator/truncateYear 10)
                         "YEAR" `(DateTruncator/truncateYear)
                         "QUARTER" `(DateTruncator/truncateQuarter)
                         "MONTH" `(DateTruncator/truncateMonth)
                         "WEEK" `(DateTruncator/truncateWeek)
                         "DAY" `(identity)
                         "HOUR" `(identity)
                         "MINUTE" `(identity)
                         "SECOND" `(identity)
                         "MILLISECOND" `(identity)
                         "MICROSECOND" `(identity))
                       (.toEpochDay)))})

(defn ->period-duration
  ([^Period p]
   (->period-duration p (Duration/ofSeconds 0)))
  ([^Period p ^Duration d]
   (PeriodDuration. p d)))

(defn truncated-millennium [^Period period]
  (let [months (.toTotalMonths period)]
    (Period/ofMonths (- months (rem months 12000)))))

(defn truncated-century [^Period period]
  (let [months (.toTotalMonths period)]
    (Period/ofMonths (- months (rem months 1200)))))

(defn truncated-decade [^Period period]
  (let [months (.toTotalMonths period)]
    (Period/ofMonths (- months (rem months 120)))))

(defn truncated-year [^Period period]
  (let [months (.toTotalMonths period)]
    (Period/ofMonths (- months (rem months 12)))))

(defn truncated-quarter [^Period period]
  (let [months (.toTotalMonths period)]
    (Period/ofMonths (- months (rem months 3)))))

(defn truncated-month [^Period period]
  (let [months (.toTotalMonths period)]
    (Period/ofMonths months)))

(defn truncated-week [^Period period]
  (let [day (.getDays period)]
    (Period/of (.getYears period) (.getMonths period) (- day (rem day 7)))))

(defmethod expr/codegen-call [:date_trunc :utf8 :interval] [{[{field :literal} _] :args, [_ [_interval _interval-unit :as interval-type]] :arg-types}]
  {:return-type interval-type
   :->call-code (fn [[_ pd]]
                  (let [period `(.getPeriod ^PeriodDuration ~pd)
                        duration `(.getDuration ^PeriodDuration ~pd)]
                    (case field
                      "MILLENNIUM" `(->period-duration (truncated-millennium ~period))
                      "CENTURY" `(->period-duration (truncated-century ~period))
                      "DECADE" `(->period-duration (truncated-decade ~period))
                      "YEAR" `(->period-duration (truncated-year ~period))
                      "QUARTER" `(->period-duration (truncated-quarter ~period))
                      "MONTH" `(->period-duration (truncated-month ~period))
                      "WEEK" `(->period-duration (truncated-week ~period))
                      "DAY" `(->period-duration ~period)
                      "HOUR" `(->period-duration ~period (.truncatedTo ~duration ChronoUnit/HOURS))
                      "MINUTE" `(->period-duration ~period (.truncatedTo ~duration ChronoUnit/MINUTES))
                      "SECOND" `(->period-duration ~period (.truncatedTo ~duration ChronoUnit/SECONDS))
                      "MILLISECOND" `(->period-duration ~period (.truncatedTo ~duration ChronoUnit/MILLIS))
                      "MICROSECOND" `(->period-duration ~period (.truncatedTo ~duration ChronoUnit/MICROS)))))})

(defn- bound-precision ^long [^long precision]
  (-> precision (max 0) (min 9)))

(def ^:private precision-timeunits
  [:second
   :milli :milli :milli
   :micro :micro :micro
   :nano :nano :nano])

(def ^:private seconds-multiplier (mapv long [1e0 1e3 1e3 1e3 1e6 1e6 1e6 1e9 1e9 1e9]))
(def ^:private nanos-divisor (mapv long [1e9 1e6 1e6 1e6 1e3 1e3 1e3 1e0 1e0 1e0]))
(def ^:private precision-modulus (mapv long [1e0 1e2 1e1 1e0 1e2 1e1 1e0 1e2 1e1 1e0]))

(defn- truncate-for-precision [code precision]
  (let [^long modulus (precision-modulus precision)]
    (if (= modulus 1)
      code
      `(* ~modulus (quot ~code ~modulus)))))

(defn- current-timestamp [^long precision]
  (let [precision (bound-precision precision)
        zone-id (.getZone expr/*clock*)]
    {:return-type [:timestamp-tz (precision-timeunits precision) (str zone-id)]
     :->call-code (fn [_]
                    (-> `(long (let [inst# (.instant expr/*clock*)]
                                 (+ (* ~(seconds-multiplier precision) (.getEpochSecond inst#))
                                    (quot (.getNano inst#) ~(nanos-divisor precision)))))
                        (truncate-for-precision precision)))}))

(def ^:private default-time-precision 6)

(defmethod expr/codegen-call [:current_timestamp] [_]
  (current-timestamp default-time-precision))

(defmethod expr/codegen-call [:current_timestamp :int] [{[{precision :literal}] :args}]
  (assert (integer? precision) "precision must be literal for now")
  (current-timestamp precision))

(defmethod expr/codegen-call [:current_date] [_]
  ;; TODO check the specs on this one - I read the SQL spec as being returned in local,
  ;; but Arrow expects Dates to be in UTC.
  ;; we then turn DateDays into LocalDates, which confuses things further.
  {:return-type [:date :day]
   :->call-code (fn [_]
                  `(long (-> (ZonedDateTime/ofInstant (.instant expr/*clock*) ZoneOffset/UTC)
                             (.toLocalDate)
                             (.toEpochDay))))})

(defn- current-time [^long precision]
  ;; TODO check the specs on this one - I read the SQL spec as being returned in local,
  ;; but Arrow expects Times to be in UTC.
  ;; we then turn times into LocalTimes, which confuses things further.
  (let [precision (bound-precision precision)]
    {:return-type [:time-local (precision-timeunits precision)]
     :->call-code (fn [_]
                    (-> `(long (-> (ZonedDateTime/ofInstant (.instant expr/*clock*) ZoneOffset/UTC)
                                   (.toLocalTime)
                                   (.toNanoOfDay)
                                   (quot ~(nanos-divisor precision))))
                        (truncate-for-precision precision)))}))

(defmethod expr/codegen-call [:current_time] [_]
  (current-time default-time-precision))

(defmethod expr/codegen-call [:current_time :int] [{[{precision :literal}] :args}]
  (assert (integer? precision) "precision must be literal for now")
  (current-time precision))

(defn- local-timestamp [^long precision]
  (let [precision (bound-precision precision)]
    {:return-type [:timestamp-local (precision-timeunits precision)]
     :->call-code (fn [_]
                    (-> `(long (let [ldt# (-> (ZonedDateTime/ofInstant (.instant expr/*clock*) (.getZone expr/*clock*))
                                              (.toLocalDateTime))]
                                 (+ (* (.toEpochSecond ldt# ZoneOffset/UTC) ~(seconds-multiplier precision))
                                    (quot (.getNano ldt#) ~(nanos-divisor precision)))))
                        (truncate-for-precision precision)))}))

(defmethod expr/codegen-call [:local_timestamp] [_]
  (local-timestamp default-time-precision))

(defmethod expr/codegen-call [:local_timestamp :num] [{[{precision :literal}] :args}]
  (assert (integer? precision) "precision must be literal for now")
  (local-timestamp precision))

(defn- local-time [^long precision]
  (let [precision (bound-precision precision)
        time-unit (precision-timeunits precision)]
    {:return-type [:time-local time-unit]
     :->call-code (fn [_]
                    (-> `(long (-> (ZonedDateTime/ofInstant (.instant expr/*clock*) (.getZone expr/*clock*))
                                   (.toLocalTime)
                                   (.toNanoOfDay)
                                   (quot ~(nanos-divisor precision))))
                        (truncate-for-precision precision)))}))

(defmethod expr/codegen-call [:local_time] [_]
  (local-time default-time-precision))

(defmethod expr/codegen-call [:local_time :int] [{[{precision :literal}] :args}]
  (assert (integer? precision) "precision must be literal for now")
  (local-time precision))

(defmethod expr/codegen-call [:abs :num] [{[numeric-type] :arg-types}]
  {:return-type numeric-type
   :->call-code #(do `(Math/abs ~@%))})

(defn invalid-period-err [^long from-µs, ^long to-µs]
  (let [from (time/micros->instant from-µs)
        to (time/micros->instant to-µs)]
    (err/runtime-err :core2.temporal/invalid-period
                     {::err/message
                      (format "From cannot be greater than to when constructing a period - from: %s, to %s" from to)
                      :from from
                      :to to})))

(defn ->period ^java.util.Map [^long from, ^long to]
  ;; TODO error assumes micros
  (when (> from to)
    (throw (invalid-period-err from to)))

  {"xt$from" (doto (ValueBox.) (.writeLong from))
   "xt$to" (doto (ValueBox.) (.writeLong to))})

(defmethod expr/codegen-call [:period :timestamp-tz :timestamp-tz] [{[from-type to-type] :arg-types}]
  {:return-type [:struct {'xt$from from-type, 'xt$to to-type}]
   :->call-code (fn [[from-code to-code]]
                  (-> `(->period ~from-code ~to-code)
                      (expr/with-tag Map)))})

(defn ->open-ended-period [^long from]
  {"xt$from" (doto (ValueBox.) (.writeLong from))
   "xt$to" (doto (ValueBox.) (.writeLong Long/MAX_VALUE))})

(defmethod expr/codegen-call [:period :timestamp-tz :null] [{[from-type _to-type] :arg-types}]
  {:return-type [:struct {'xt$from from-type, 'xt$to types/temporal-col-type}]
   :->call-code (fn [[from-code _to-code]]
                  (-> `(->open-ended-period ~from-code)
                      (expr/with-tag Map)))})

(defn from ^long [^Map period]
  (.readLong ^IValueReader (.get period "xt$from")))

(defn to ^long [^Map period]
  (.readLong ^IValueReader (.get period "xt$to")))

(defn temporal-contains-point? [p1 ^long ts]
  (and (<= (from p1) ts)
       (>= (to p1) ts)))

(defmethod expr/codegen-call [:contains? :struct :timestamp-tz] [_]
  {:return-type :bool
   :->call-code (fn [[p1-code ts-code]]
                  `(temporal-contains-point? ~p1-code ~ts-code))})

(defn temporal-contains? [p1 p2]
  (and (<= (from p1) (from p2))
       (>= (to p1) (to p2))))

(defn temporal-strictly-contains? [p1 p2]
  (and (< (from p1) (from p2))
       (> (to p1) (to p2))))

(defn overlaps? [p1 p2]
  (and (< (from p1) (to p2))
       (> (to p1) (from p2))))

(defn strictly-overlaps? [p1 p2]
  (and (> (from p1) (from p2))
       (< (to p1) (to p2))))

(defn equals? [p1 p2]
  (and (= (from p1) (from p2))
       (= (to p1) (to p2))))

(defn precedes? [p1 p2]
  (<= (to p1) (from p2)))

(defn strictly-precedes? [p1 p2]
  (< (to p1) (from p2)))

(defn immediately-precedes? [p1 p2]
  (= (to p1) (from p2)))

(defn succeeds? [p1 p2]
  (>= (from p1) (to p2)))

(defn strictly-succeeds? [p1 p2]
  (> (from p1) (to p2)))

(defn immediately-succeeds? [p1 p2]
  (= (from p1) (to p2)))

(defn leads? [p1 p2]
  (and (< (from p1) (from p2))
       (< (from p2) (to p1))
       (<= (to p1) (to p2))))

(defn strictly-leads? [p1 p2]
  (and (< (from p1) (from p2))
       (< (from p2) (to p1))
       (< (to p1) (to p2))))

(defn immediately-leads? [p1 p2]
  (and (< (from p1) (from p2))
       (= (to p1) (to p2))))

(defn lags? [p1 p2]
  (and (>= (from p1) (from p2))
       (< (from p2) (to p1))
       (> (to p1) (to p2))))

(defn strictly-lags? [p1 p2]
  (and (> (from p1) (from p2))
       (< (from p2) (to p1))
       (> (to p1) (to p2))))

(defn immediately-lags? [p1 p2]
  (and (= (from p1) (from p2))
       (> (to p1) (to p2))))

(doseq [[pred-name pred-sym] [[:contains `temporal-contains?]
                              [:strictly_contains `temporal-strictly-contains?]
                              [:overlaps? `overlaps?]
                              [:strictly_overlaps `strictly-overlaps?]
                              [:equals? `equals?]
                              [:precedes? `precedes?]
                              [:strictly_precedes `strictly-precedes?]
                              [:immediately_precedes `immediately-precedes?]
                              [:succeeds `succeeds?]
                              [:strictly_succeeds `strictly-succeeds?]
                              [:immediately_succeeds `immediately-succeeds?]
                              [:leads `leads?]
                              [:strictly_leads `strictly-leads?]
                              [:immediately_leads `immediately-leads?]
                              [:lags `lags?]
                              [:strictly_lags `strictly-lags?]
                              [:immediately_lags `immediately-lags?]]]
  (defmethod expr/codegen-call [pred-name :struct :struct] [_]
    {:return-type :bool
     :->call-code (fn [[p1-code p2-code]]
                    `(~pred-sym ~p1-code ~p2-code))})

  ;; add aliases with `?` suffix
  (defmethod expr/codegen-call [(keyword (str (name pred-name) "?")) :struct :struct] [expr]
    (expr/codegen-call (assoc expr :f pred-name))))
