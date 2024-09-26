(ns xtdb.pgwire
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.api :as xt]
            [xtdb.expression :as expr]
            [xtdb.node :as xtn]
            [xtdb.node.impl]
            [xtdb.query]
            [xtdb.serde :as serde]
            [xtdb.sql.plan :as plan]
            [xtdb.time :as time]
            [xtdb.types :as types]
            [xtdb.util :as util])
  (:import [clojure.lang PersistentQueue]
           [java.io ByteArrayInputStream ByteArrayOutputStream Closeable DataInputStream DataOutputStream EOFException IOException InputStream OutputStream PushbackInputStream]
           [java.lang Thread$State]
           [java.net ServerSocket Socket SocketException]
           [java.nio.charset StandardCharsets]
           [java.nio.file Path]
           [java.security KeyStore]
           [java.time Clock Duration LocalDate LocalDateTime OffsetDateTime Period ZoneId ZonedDateTime]
           [java.util List Map]
           [java.util.concurrent ConcurrentHashMap ExecutorService Executors TimeUnit]
           [java.util.function Consumer]
           [javax.net.ssl KeyManagerFactory SSLContext SSLSocket]
           (org.antlr.v4.runtime ParserRuleContext)
           [org.apache.arrow.vector PeriodDuration]
           org.postgresql.util.PGobject
           (xtdb.antlr SqlVisitor)
           [xtdb.api ServerConfig Xtdb$Config]
           xtdb.api.module.XtdbModule
           xtdb.IResultCursor
           xtdb.node.impl.IXtdbInternal
           (xtdb.query BoundQuery PreparedQuery)
           [xtdb.types IntervalDayTime IntervalMonthDayNano IntervalYearMonth]
           [xtdb.vector IVectorReader RelationReader]))

;; references
;; https://www.postgresql.org/docs/current/protocol-flow.html
;; https://www.postgresql.org/docs/current/protocol-message-formats.html

;;; Server
;; Represents a single postgres server on a particular port
;; the server currently is a blocking IO server (no nio / netty)
;; the server does not own the lifecycle of its associated node

;; Notes:
;; repl became unresponsive when protcol was stuck in the wrong state
;; :extended when it should have been :simple, maybe this is nothing.

(defrecord Server [port

                   ;; server socket and thread for accepting connections
                   ^ServerSocket accept-socket
                   ^Thread accept-thread

                   ;; a thread pool for handing off connections (currently using blocking io)
                   ^ExecutorService thread-pool

                   !closing?

                   ;; atom to hold server state, such as current connections, and the connection :cid counter.
                   server-state

                   !tmp-nodes]
  XtdbModule
  (close [_]
    (when (compare-and-set! !closing? false true)
      (when-not (#{Thread$State/NEW, Thread$State/TERMINATED} (.getState accept-thread))
        (log/trace "Closing accept thread")
        (.interrupt accept-thread)
        (when-not (.join accept-thread (Duration/ofSeconds 5))
          (log/error "Could not shut down accept-thread gracefully" {:port port, :thread (.getName accept-thread)})))

      (let [drain-wait (:drain-wait @server-state 5000)]
        (when-not (contains? #{0, nil} drain-wait)
          (log/trace "Server draining connections")
          (let [wait-until (+ (System/currentTimeMillis) drain-wait)]
            (loop []
              (cond
                ;; connections all closed, proceed
                (empty? (:connections @server-state)) nil

                (Thread/interrupted) (log/warn "Interrupted during drain, force closing")

                ;; timeout
                (< wait-until (System/currentTimeMillis))
                (log/warn "Could not drain connections in time, force closing")

                :else (do (Thread/sleep 10) (recur))))))

        ;; force stopping conns
        (util/try-close (vals (:connections @server-state)))

        (when-not (.isShutdown thread-pool)
          (log/trace "Closing thread pool")
          (.shutdownNow thread-pool)
          (when-not (.awaitTermination thread-pool 5 TimeUnit/SECONDS)
            (log/error "Could not shutdown thread pool gracefully" {:port port})))

        (when !tmp-nodes
          (log/debug "closing tmp nodes")
          (util/close !tmp-nodes))

        (log/info "Server stopped.")))))

;;; Connection
;; Represents a single client connection to the server
;; identified by an integer 'cid'
;; each connection holds some state under :conn-state (such as prepared statements, session params and such)
;; and is registered under the :connections map under the servers :server-state.

(defrecord Connection [^Server server
                       node
                       ^Socket socket

                       ;; a positive integer that identifies the connection on this server
                       ;; we will use this as the pg Process ID for messages that require it (such as cancellation)
                       cid

                       ;; atom to mediate lifecycle transitions (see Connection lifecycle comment)
                       !closing?

                       ;; atom to hold a map of session / connection state, such as :prepared-statements, :session, :transaction.
                       conn-state

                       ;; io
                       ^DataInputStream in
                       ^DataOutputStream out]

  Closeable
  (close [_]
    (when-not (.isClosed socket)
      (util/try-close socket))

    (let [{:keys [server-state]} server]
      (swap! server-state update :connections dissoc cid))

    (log/debug "Connection ended" {:cid cid})))

;; best the server/conn records are opaque when printed as they contain mutual references
(defmethod print-method Server [wtr o] ((get-method print-method Object) wtr o))
(defmethod print-method Connection [wtr o] ((get-method print-method Object) wtr o))

(defn- read-c-string
  "Postgres strings are null terminated, reads a null terminated utf-8 string from in."
  ^String [^InputStream in]
  (loop [baos (ByteArrayOutputStream.)
         x (.read in)]
    (if (zero? x)
      (String. (.toByteArray baos) StandardCharsets/UTF_8)
      (recur (doto baos
               (.write x))
             (.read in)))))

(defn- write-c-string
  "Postgres strings are null terminated, writes a null terminated utf8 string to out."
  [^OutputStream out ^String s]
  (.write out (.getBytes s StandardCharsets/UTF_8))
  (.write out 0))

(def ^:private oid-varchar (get-in types/pg-types [:varchar :oid]))
(def ^:private oid-json (get-in types/pg-types [:json :oid]))

;; all postgres client IO arrives as either an untyped (startup) or typed message
(defn- read-untyped-msg [^DataInputStream in]
  (let [size (- (.readInt in) 4)
        barr (byte-array size)
        _ (.readFully in barr)]
    {:msg-in (DataInputStream. (ByteArrayInputStream. barr))
     :msg-len size}))

(defn- read-typed-msg [^DataInputStream in]
  (let [type-byte (.readUnsignedByte in)]
    (assoc (read-untyped-msg in)
      :msg-char8 (char type-byte))))

;;; errors

(defn- err-protocol-violation [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "08P01"
   :message msg})

;;TODO parse errors should return a PSQL parse error
;;this code is generic, but there are specific ones a well
#_
(defn- err-parse [parse-failure]
  (let [lines (str/split-lines (parser/failure->str parse-failure))]
    {:severity "ERROR"
     :localized-severity "ERROR"
     :sql-state "42601"
     :message (first lines)
     :detail (str/join "\n" (rest lines))
     :position (str (inc (:idx parse-failure)))}))

(defn- err-internal [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "XX000"
   :message msg})

(defn- err-admin-shutdown [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "57P01"
   :message msg})

(defn- err-cannot-connect-now [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "57P03"
   :message msg})

(defn- err-query-cancelled [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "57014"
   :message msg})

(defn- notice-warning [msg]
  {:severity "WARNING"
   :localized-severity "WARNING"
   :sql-state "01000"
   :message msg})

(defn err-pg-exception
  "Returns a pg specific error for an XTDB exception"
  [^Throwable ex generic-msg]
  (if (or (instance? xtdb.IllegalArgumentException ex)
          (instance? xtdb.RuntimeException ex))
    (err-protocol-violation (.getMessage ex))
    (err-internal generic-msg)))

;;; sql processing
;; because we are talking to postgres clients, we cannot simply fling sql at xt (shame!)
;; so these functions provide some utility on top of xt to figure out where we can 'fake it til we make it' as a pg server.

(def ^:private canned-responses
  "Some pre-baked responses to common queries issued as setup by Postgres drivers, e.g SQLAlchemy"
  [{:q ";"
    :cols []
    :rows (fn [_conn] [])}

   ;; jdbc meta getKeywords (hibernate)
   ;; I think this should work, but it causes some kind of low level issue, likely
   ;; because our query protocol impl is broken, or partially implemented.
   ;; java.lang.IllegalStateException: Received resultset tuples, but no field structure for them
   {:q "select string_agg(word, ',') from pg_catalog.pg_get_keywords()"
    :cols [{:column-name "col1" :column-oid oid-varchar}]
    :rows (fn [_conn] [["xtdb"]])}])

(defn- trim-sql [s]
  (-> s (str/triml) (str/replace #";\s*$" "")))

;; yagni, is everything upper'd anyway by drivers / server?
(defn- probably-same-query? [s substr]
  ;; TODO I bet this may cause some amusement. Not sure what to do about non-parsed query matching, it'll do for now.
  (str/starts-with? (str/lower-case s) (str/lower-case substr)))

(defn get-canned-response
  "If the sql string is a canned response, returns the entry from the canned-responses that matches."
  [sql-str]
  (when sql-str (first (filter #(probably-same-query? sql-str (:q %)) canned-responses))))

(defn- strip-semi-colon [s] (if (str/ends-with? s ";") (subs s 0 (dec (count s))) s))

(defn- statement-head [s]
  (-> s (str/split #"\s+") first str/upper-case strip-semi-colon))

(defn session-param-name [^ParserRuleContext ctx]
  (some-> ctx
          (.accept (reify SqlVisitor
                     (visitRegularIdentifier [_ ctx] (.getText ctx))
                     (visitDelimitedIdentifier [_ ctx]
                       (let [di-str (.getText ctx)]
                         (subs di-str 1 (dec (count di-str)))))))
          (str/lower-case)))

(defn- interpret-sql [sql {:keys [default-tz latest-submitted-tx]}]
  (log/debug "Interpreting SQL: " sql)
  (let [sql-trimmed (trim-sql sql)]
    (or (when (str/blank? sql-trimmed)
          {:statement-type :empty-query})

        (when-some [canned-response (get-canned-response sql-trimmed)]
          {:statement-type :canned-response, :canned-response canned-response})

        (try
          (.accept (plan/parse-statement sql-trimmed)
                   (reify SqlVisitor
                     (visitDirectSqlStatement [this ctx] (.accept (.directlyExecutableStatement ctx) this))

                     (visitSetSessionVariableStatement [_ ctx]
                       {:statement-type :set-session-parameter
                        :parameter (session-param-name (.identifier ctx))
                        :value (-> (.literal ctx)
                                   (.accept (plan/->ExprPlanVisitor nil nil)))})

                     (visitSetSessionCharacteristicsStatement [this ctx]
                       {:statement-type :set-session-characteristics
                        :session-characteristics
                        (into {} (mapcat #(.accept ^ParserRuleContext % this)) (.sessionCharacteristic ctx))})

                     (visitSessionTxCharacteristics [this ctx]
                       (let [[^ParserRuleContext session-mode & more-modes] (.sessionTxMode ctx)]
                         (assert (nil? more-modes) "pgwire only supports one for now")
                         (.accept session-mode this)))

                     (visitSetTransactionStatement [this ctx]
                       {:statement-type :set-transaction
                        :tx-characteristics (.accept (.transactionCharacteristics ctx) this)})

                     (visitStartTransactionStatement [this ctx]
                       {:statement-type :begin
                        :tx-characteristics (some-> (.transactionCharacteristics ctx) (.accept this))})

                     (visitTransactionCharacteristics [this ctx]
                       (into {} (mapcat #(.accept ^ParserRuleContext % this)) (.transactionMode ctx)))

                     (visitIsolationLevel [_ _] {})
                     (visitSessionIsolationLevel [_ _] {})

                     (visitReadWriteTransaction [_ _] {:access-mode :read-write})
                     (visitReadOnlyTransaction [_ _] {:access-mode :read-only})

                     (visitReadWriteSession [_ _] {:access-mode :read-write})
                     (visitReadOnlySession [_ _] {:access-mode :read-only})

                     (visitTransactionSystemTime [_ ctx]
                       {:tx-system-time (.accept (.dateTimeLiteral ctx)
                                                 (reify SqlVisitor
                                                   (visitDateLiteral [_ ctx]
                                                     (-> (LocalDate/parse (.accept (.characterString ctx) plan/string-literal-visitor))
                                                         (.atStartOfDay)
                                                         (.atZone ^ZoneId default-tz)))

                                                   (visitTimestampLiteral [_ ctx]
                                                     (let [ts (time/parse-sql-timestamp-literal (.accept (.characterString ctx) plan/string-literal-visitor))]
                                                       (cond
                                                         (instance? LocalDateTime ts) (.atZone ^LocalDateTime ts ^ZoneId default-tz)
                                                         (instance? ZonedDateTime ts) ts)))))})

                     (visitCommitStatement [_ _] {:statement-type :commit})
                     (visitRollbackStatement [_ _] {:statement-type :rollback})

                     (visitSetTimeZoneStatement [_ ctx]
                       ;; not sure if handlling time zone explicitly is the right approach
                       ;; might be cleaner to handle it like any other session param
                       {:statement-type :set-time-zone
                        :tz (let [v (.getText (.characterString ctx))]
                              (subs v 1 (dec (count v)))) })

                     (visitInsertStmt [this ctx] (-> (.insertStatement ctx) (.accept this)))

                     (visitInsertStatement [_ _]
                       {:statement-type :dml, :dml-type :insert
                        :query sql, :transformed-query sql-trimmed})

                     (visitUpdateStmt [this ctx] (-> (.updateStatementSearched ctx) (.accept this)))

                     (visitUpdateStatementSearched [_ _]
                       {:statement-type :dml, :dml-type :update
                        :query sql, :transformed-query sql-trimmed})

                     (visitDeleteStmt [this ctx] (-> (.deleteStatementSearched ctx) (.accept this)))

                     (visitDeleteStatementSearched [_ _]
                       {:statement-type :dml, :dml-type :delete
                        :query sql, :transformed-query sql-trimmed})

                     (visitEraseStmt [this ctx] (-> (.eraseStatementSearched ctx) (.accept this)))

                     (visitEraseStatementSearched [_ _]
                       {:statement-type :dml, :dml-type :erase
                        :query sql, :transformed-query sql-trimmed})

                     (visitAssertStatement [_ _]
                       {:statement-type :dml, :dml-type :assert
                        :query sql, :transformed-query sql-trimmed})

                     (visitQueryExpr [this ctx]
                       (let [q {:statement-type :query, :query sql, :transformed-query sql-trimmed}]
                         (->> (some-> (.settingQueryVariables ctx) (.settingQueryVariable))
                              (transduce (keep (partial plan/accept-visitor this)) conj q))))

                     ;; handled in plan
                     (visitSettingDefaultValidTime [_ _])
                     (visitSettingDefaultSystemTime [_ _])

                     (visitSettingCurrentTime [_ ctx]
                       [:current-time (time/->instant (.accept (.currentTime ctx) (plan/->ExprPlanVisitor nil nil)))])

                     (visitSettingBasis [_ ctx]
                       (let [at-tx (.accept (.basis ctx) (plan/->ExprPlanVisitor nil nil))]
                         [:at-tx (serde/map->TxKey {:system-time (time/->instant at-tx)})]))

                     (visitShowVariableStatement [_ _]
                       {:statement-type :query, :query sql, :transformed-query sql-trimmed})

                     (visitShowLatestSubmittedTransactionStatement [_ _]
                       {:statement-type :query, :query sql, :transformed-query sql-trimmed
                        :ra-plan [:table '[tx_id system_time]
                                  (if-let [{:keys [tx-id system-time]} latest-submitted-tx]
                                    [{:tx_id tx-id, :system_time system-time}]
                                    [])]})))

          (catch Exception e
            (log/debug e "Error parsing SQL")
            {:err (err-pg-exception e "error parsing sql")})))))

(defn- json-clj
  "This function is temporary, the long term goal will be to walk arrow directly to generate the json (and later monomorphic
  results).

  Returns a clojure representation of the value, which - when printed as json will present itself as the desired json type string."
  [obj]

  ;; we do not extend any jackson/data.json/whatever because we want intentional printing of supported types and
  ;; text representation (e.g consistent scientific notation, dates and times, etc).

  ;; we can reduce the cost of this walk later by working directly on typed arrow vectors rather than dynamic clojure maps!
  ;; we lean on data.json for now for encoding, quote/escape, json compat floats etc

  (cond
    (nil? obj) nil
    (boolean? obj) obj
    (int? obj) obj

    ;; I am allowing string pass through for now but be aware data.json escapes unicode and that may not be
    ;; what we want at some point (e.g pass plain utf8 unless prompted as a param).
    (string? obj) obj

    ;; bigdec cast gets us consistent exponent notation E with a sign for doubles, floats and bigdecs.
    (float? obj) (bigdec obj)

    ;; java.time datetime-ish toString is already iso8601, we may want to tweak this later
    ;; as the string format often omits redundant components (like trailing seconds) which may make parsing harder
    ;; for clients, doesn't matter for now - json probably gonna die anyway
    (instance? LocalDate obj) (str obj)
    (instance? LocalDateTime obj) (str obj)
    (instance? OffsetDateTime obj) (str obj)
    ;; print offset instead of zoneprefix  otherwise printed representation may change depending on client
    ;; we might later revisit this if json printing remains
    (instance? ZonedDateTime obj) (recur (.toOffsetDateTime ^ZonedDateTime obj))
    (instance? Duration obj) (str obj)
    (instance? Period obj) (str obj)

    (instance? IntervalYearMonth obj) (str obj)
    (instance? IntervalDayTime obj) (str obj)
    (instance? IntervalMonthDayNano obj) (str obj)

    ;; represent period duration as an iso8601 duration string (includes period components)
    (instance? PeriodDuration obj)
    (let [^PeriodDuration obj obj
          period (.getPeriod obj)
          duration (.getDuration obj)]
      (cond
        ;; if either component is zero (likely in sql), we can just print the objects
        ;; not normalizing the period here, should we be?
        (.isZero period) (str duration)
        (.isZero duration) (str period)

        :else
        ;; otherwise the duration needs to be append to the period, with a T on front.
        ;; e.g P1DT3S - unfortunately, durations are printed with the ISO8601 PT header
        ;; Right now doesn't matter - so just string munging.
        (let [pstr (str period)
              dstr (str duration)]
          (str pstr (subs dstr 1)))))

    ;; returned to handle big utf8 bufs, right now we do not handle this well, later we will be writing json with less
    ;; copies and we may encode the quoted json string straight out of the buffer
    (instance? org.apache.arrow.vector.util.Text obj) (str obj)

    ;; java list, e.g arrow JsonStringArrayList, Vectors etc, walk its members (its a json array).
    (instance? List obj) (mapv json-clj obj)

    ;; maps, cannot be created from SQL yet, but working playground requires them
    ;; we are not dealing with the possibility of non kw/string keys, xt shouldn't return maps like that right now.
    (instance? Map obj) (update-vals obj json-clj)

    (or (instance? xtdb.RuntimeException obj)
        (instance? xtdb.IllegalArgumentException obj))
    (json-clj (-> (ex-data obj)
                  (assoc :message (ex-message obj))))

    (instance? clojure.lang.Keyword obj) (json-clj (str (symbol obj)))

    :else
    (throw (Exception. (format "Unexpected type encountered by pgwire (%s)" (class obj))))))

(defn- read-startup-parameters [^DataInputStream in]
  (loop [in (PushbackInputStream. in)
         acc {}]
    (let [x (.read in)]
      (if (zero? x)
        acc
        (do (.unread in (byte x))
            (recur in (assoc acc (read-c-string in) (read-c-string in))))))))

;; startup negotiation utilities (see cmd-startup)

(def ^:private version-messages
  "Integer codes sent by the client to identify a startup msg"
  {
   ;; this is the normal 'hey I'm a postgres client' if ssl is not requested
   196608 :30
   ;; cancellation messages come in as special startup sequences (pgwire does not handle them yet!)
   80877102 :cancel
   ;; ssl messages are used when the client either requires, prefers, or allows ssl connections.
   80877103 :ssl
   ;; gssapi encoding is not supported by xt, and we tell the client that
   80877104 :gssenc})

(defn- read-version [^DataInputStream in]
  (let [{:keys [^DataInputStream msg-in] :as msg} (read-untyped-msg in)
        version (.readInt msg-in)]
    (assoc msg :version (version-messages version))))

;;; server impl

(defn- parse-session-params [params]
  (->> params
       (into {} (mapcat (fn [[k v]]
                          (case k
                            "options" (parse-session-params (for [[_ k v] (re-seq #"-c ([\w_]*)=([\w_]*)" v)]
                                                             [k v]))
                            [[k (case k
                                  "fallback_output_format" (#{:json :transit} (util/->kebab-case-kw v))
                                  v)]]))))))

;;; pg i/o shared data types
;; our io maps just capture a paired :read fn (data-in)
;; and :write fn (data-out, val)
;; the goal is to describe the data layout of various pg messages, so they can be read and written from in/out streams
;; by generalizing a bit here, we gain a terser language for describing wire data types, and leverage
;; (e.g later we might decide to compile unboxed reader/writers using these)

(def ^:private no-read (fn [_in] (throw (UnsupportedOperationException.))))
(def ^:private no-write (fn [_out _] (throw (UnsupportedOperationException.))))

(def ^:private io-char8
  "A single byte character"
  {:read #(char (.readUnsignedByte ^DataInputStream %))
   :write #(.writeByte ^DataOutputStream %1 (byte %2))})

(def ^:private io-uint16
  "An unsigned short integer"
  {:read #(.readUnsignedShort ^DataInputStream %)
   :write #(.writeShort ^DataOutputStream %1 (short %2))})

(def ^:private io-uint32
  "An unsigned 32bit integer"
  {:read #(.readInt ^DataInputStream %)
   :write #(.writeInt ^DataOutputStream %1 (int %2))})

(def ^:private io-string
  "A postgres null-terminated utf8 string"
  {:read read-c-string
   :write write-c-string})

(defn- io-list
  "Returns an io data type for a dynamic list, takes an io def for the length (e.g io-uint16) and each element.

  e.g a list of strings (io-list io-uint32 io-string)"
  [io-len, io-el]
  (let [len-rdr (:read io-len)
        len-wtr (:write io-len)
        el-rdr (:read io-el)
        el-wtr (:write io-el)]
    {:read (fn [in]
             (vec (repeatedly (len-rdr in) #(el-rdr in))))
     :write (fn [out coll] (len-wtr out (count coll)) (run! #(el-wtr out %) coll))}))

(def ^:private io-format-code
  "Postgres format codes are integers, whose value is either

  0 (:text)
  1 (:binary)

  On read returns a keyword :text or :binary, write of these keywords will be transformed into the correct integer."
  {:read (fn [^DataInputStream in] ({0 :text, 1 :binary} (.readUnsignedShort in)))
   :write (fn [^DataOutputStream out v] (.writeShort out ({:text 0, :binary 1} v)))})

(def ^:private io-format-codes
  "A list of format codes of max len len(uint16). This is the format used by the msg-bind."
  (io-list io-uint16 io-format-code))

(defn- io-record
  "Returns an io data type for a record containing named fields. Useful for definining typed message contents.

  e.g

  (io-record
    :foo io-uint32
    :bar io-string)

  would define a record of two fields (:foo and :bar) to be read/written in the order defined. "
  [& fields]
  (let [pairs (partition 2 fields)
        ks (mapv first pairs)
        ios (mapv second pairs)
        rdrs (mapv :read ios)
        wtrs (mapv :write ios)]
    {:read (fn read-record [in]
             (loop [acc {}
                    i 0]
               (if (< i (count ks))
                 (let [k (nth ks i)
                       rdr (rdrs i)]
                   (recur (assoc acc k (rdr in))
                          (unchecked-inc-int i)))
                 acc)))
     :write (fn write-record [out m]
              (dotimes [i (count ks)]
                (let [k (nth ks i)
                      wtr (wtrs i)]
                  (wtr out (k m)))))}))

(defn- io-null-terminated-list
  "An io data type for a null terminated list of elements given by io-el."
  [io-el]
  (let [el-wtr (:write io-el)]
    {:read no-read
     :write (fn write-null-terminated-list [^DataOutputStream out coll]
              (run! (partial el-wtr out) coll)
              (.writeByte out 0))}))

(defn- io-bytes-or-null
  "An io data type for a byte array (or null), in postgres you can write the bytes of say a column as either
  len (uint32) followed by the bytes OR in the case of null, a len whose value is -1. This is how row values are conveyed
  to the client."
  [io-len]
  (let [len-rdr (:read io-len)]
    {:read (fn read-bytes-or-null [^DataInputStream in]
             (let [len (len-rdr in)]
               (when (not= -1 len)
                 (let [arr (byte-array len)]
                   (.readFully in arr)
                   arr))))
     :write (fn write-bytes-or-null [^DataOutputStream out ^bytes arr]
              (if arr
                (do (.writeInt out (alength arr))
                    (.write out arr))
                (.writeInt out -1)))}))

(def ^:private io-error-notice-field
  "An io-data type that writes a (vector/map-entry pair) k and v as an error field."
  {:read no-read
   :write (fn write-error-or-notice-field [^DataOutputStream out [k v]]
            (let [field-char8 ({:localized-severity \S
                                :severity \V
                                :sql-state \C
                                :message \M
                                :detail \D
                                :position \P
                                :where \W} k)]
              (when field-char8
                (.writeByte out (byte field-char8))
                (write-c-string out (str v)))))})

(def ^:private io-portal-or-stmt
  "An io data type that returns a keyword :portal or :prepared-statement given the next char8 in the buffer.
  This is useful for describe/close who name either a portal or statement."
  {:read (comp {\P :portal, \S :prepared-stmt} (:read io-char8)),
   :write no-write})

(def io-cancel-request
  (io-record :process-id io-uint32
             :secret-key io-uint32))

;;; msg definition

(def ^:private ^:redef client-msgs {})
(def ^:private ^:redef server-msgs {})

(defmacro ^:private def-msg
  "Defs a typed-message with the given kind (:client or :server) and fields.

  Installs the message var in the either client-msgs or server-msgs map for later retrieval by code."
  [sym kind char8 & fields]
  `(let [fields# [~@fields]
         kind# ~kind
         char8# ~char8
         {read# :read
          write# :write}
         (apply io-record fields#)]

     (def ~sym
       {:name ~(name sym)
        :kind kind#
        :char8 char8#
        :fields fields#
        :read read#
        :write write#})

     (if (= kind# :client)
       (alter-var-root #'client-msgs assoc char8# (var ~sym))
       (alter-var-root #'server-msgs assoc char8# (var ~sym)))

     ~sym))

;; client messages

(def-msg msg-bind :client \B
  :portal-name io-string
  :stmt-name io-string
  :param-format io-format-codes
  :params (io-list io-uint16 (io-bytes-or-null io-uint32))
  :result-format io-format-codes)

(def-msg msg-close :client \C
  :close-type io-portal-or-stmt
  :close-name io-string)

(def-msg msg-copy-data :client \d)
(def-msg msg-copy-done :client \c)
(def-msg msg-copy-fail :client \f)

(def-msg msg-describe :client \D
  :describe-type io-portal-or-stmt
  :describe-name io-string)

(def-msg msg-execute :client \E
  :portal-name io-string
  :limit io-uint32)

(def-msg msg-flush :client \H)

(def-msg msg-parse :client \P
  :stmt-name io-string
  :query io-string
  :arg-types (io-list io-uint16 io-uint32))

(def-msg msg-password :client \p)

(def-msg msg-simple-query :client \Q
  :query io-string)

(def-msg msg-sync :client \S)

(def-msg msg-terminate :client \X)

;;; server messages

(def-msg msg-error-response :server \E
  :error-fields (io-null-terminated-list io-error-notice-field))

(def-msg msg-notice-response :server \N
  :notice-fields (io-null-terminated-list io-error-notice-field))

(def-msg msg-bind-complete :server \2
  :result {:read no-read
           :write (fn [^DataOutputStream out _] (.writeInt out 4))})

(def-msg msg-close-complete :server \3)

(def-msg msg-command-complete :server \C
  :command io-string)

(def-msg msg-parameter-description :server \t
  :parameter-oids (io-list io-uint16 io-uint32))

(def-msg msg-parameter-status :server \S
  :parameter io-string
  :value io-string)

(def-msg msg-data-row :server \D
  :vals (io-list io-uint16 (io-bytes-or-null io-uint32)))

(def-msg msg-portal-suspended :server \s)

(def-msg msg-parse-complete :server \1)

(def-msg msg-no-data :server \n)

(def-msg msg-empty-query :server \I)


(def-msg msg-auth :server \R
  :result io-uint32)

(def-msg msg-backend-key-data :server \K
  :process-id io-uint32
  :secret-key io-uint32)

(def-msg msg-copy-in-response :server \G)

(def-msg msg-ready :server \Z
  :status {:read no-read
           :write (fn [^DataOutputStream out status]
                    (.writeByte out (byte ({:idle \I
                                            :transaction \T
                                            :failed-transaction \E}
                                           status))))})

(def-msg msg-row-description :server \T
  :columns (->> (io-record
                  :column-name io-string
                  :table-oid io-uint32
                  :column-attribute-number io-uint16
                  :column-oid io-uint32
                  :typlen  io-uint16
                  :type-modifier io-uint32
                  :result-format io-format-code)
                (io-list io-uint16)))

;;; server commands
;; the commands represent actions the connection may take in response to some message
;; they are simple functions that can call each other directly, though they can also be enqueued
;; through the connections :cmd-buf queue (in :conn-state) this will later be useful
;; for shared concerns (e.g 'what is a connection doing') and to allow for termination mid query
;; in certain scenarios

(defn- cmd-write-msg
  "Writes out a single message given a definition (msg-def) and optional data record."
  ([conn msg-def]
   (log/trace "Writing server message" (select-keys msg-def [:char8 :name]))
   (let [^DataOutputStream out (:out conn)]
     (.writeByte out (byte (:char8 msg-def)))
     (.writeInt out 4)))
  ([conn msg-def data]
   (log/trace "Writing server message (with body)" (select-keys msg-def [:char8 :name]))
   (let [^DataOutputStream out (:out conn)
         bytes-out (ByteArrayOutputStream.)
         msg-out (DataOutputStream. bytes-out)
         _ ((:write msg-def) msg-out data)
         arr (.toByteArray bytes-out)]
     (.writeByte out (byte (:char8 msg-def)))
     (.writeInt out (+ 4 (alength arr)))
     (.write out arr))))

(def time-zone-nf-param-name "timezone")
(def pg-param-nf->display-format
  {time-zone-nf-param-name "TimeZone"})

(defn- set-session-parameter [conn parameter value]
  ;;https://www.postgresql.org/docs/current/config-setting.html#CONFIG-SETTING-NAMES-VALUES
  ;;parameter names are case insensitive, choosing to lossily downcase them for now and store a mapping to a display format
  ;;for any name not typically displayed in lower case.
  (swap! (:conn-state conn) update-in [:session :parameters] (fnil into {}) (parse-session-params {parameter value}))
  (cmd-write-msg conn msg-parameter-status {:parameter (get pg-param-nf->display-format parameter parameter), :value (str value)}))

(defn cmd-send-ready
  "Sends a msg-ready with the given status - eg (cmd-send-ready conn :idle).
  If the status is omitted, the status is determined from whether a transaction is currently open."
  ([conn]
   ;; it would be good to look at ready status being automatically driven by pure conn state, this is a bit messy.
   (if-some [transaction (:transaction @(:conn-state conn))]
     (if (:failed transaction)
       (cmd-send-ready conn :failed-transaction)
       (cmd-send-ready conn :transaction))
     (cmd-send-ready conn :idle)))
  ([conn status]
   (cmd-write-msg conn msg-ready {:status status})))

(defn cmd-send-error
  "Sends an error back to the client (e.g (cmd-send-error conn (err-protocol \"oops!\")).

  If the connection is operating in the :extended protocol mode, any error causes the connection to skip
  messages until a msg-sync is received."
  [{:keys [conn-state] :as conn} err]

  ;; error seen while in :extended mode, start skipping messages until sync received
  (when (= :extended (:protocol @conn-state))
    (swap! conn-state assoc :skip-until-sync true))

  ;; mark a transaction (if open as failed), for now we will consider all errors to do this
  (swap! conn-state util/maybe-update :transaction assoc :failed true, :err err)

  (cmd-write-msg conn msg-error-response {:error-fields err}))

(defn cmd-send-notice
  "Sends an notice message back to the client (e.g (cmd-send-notice conn (warning \"You are doing this wrong!\"))."
  [conn notice]
  (cmd-write-msg conn msg-notice-response {:notice-fields notice}))

(defn cmd-write-canned-response [conn {:keys [q rows] :as _canned-resp}]
  (let [rows (rows conn)]
    (doseq [row rows]
      (cmd-write-msg conn msg-data-row {:vals (mapv (fn [v] (if (bytes? v) v (types/utf8 v))) row)}))

       (cmd-write-msg conn msg-command-complete {:command (str (statement-head q) " " (count rows))})))

(defn- close-portal
  [{:keys [conn-state, cid]} portal-name]
  (log/trace "Closing portal" {:cid cid, :portal portal-name})
  (when-some [portal (get-in @conn-state [:portals portal-name])]

    ;; close the portal/boundQuery (specifically any resources opened by binding params)
    (util/close (:bound-query portal))

    ;; remove portal from stmt
    (swap! conn-state update-in [:prepared-statements (:stmt-name portal) :portals] disj portal-name)

    ;; remove from root
    (swap! conn-state update :portals dissoc portal-name)))

(defn cmd-close
  "Closes a prepared statement or portal that was opened with bind / parse."
  [{:keys [conn-state, cid] :as conn} {:keys [close-type, close-name]}]

  (case close-type
    :prepared-stmt
    (do
      (log/trace "Closing prepared statement" {:cid cid, :stmt close-name})
      (when-some [stmt (get-in @conn-state [:prepared-statements close-name])]

        ;; close associated portals
        (doseq [portal-name (:portals stmt)]
          (close-portal conn portal-name))

        ;; remove from root
        (swap! conn-state update :prepared-statements dissoc close-name)))

    :portal
    (close-portal conn close-name)

    nil)

  (cmd-write-msg conn msg-close-complete))

(defn cmd-terminate
  "Causes the connection to start closing."
  [{:keys [!closing?]}]
  (reset! !closing? true))

(defn set-time-zone [{:keys [conn-state] :as conn} tz]
  (swap! conn-state update-in [:session :clock] (fn [^Clock clock]
                                                  (.withZone clock (ZoneId/of tz))))
  (set-session-parameter conn time-zone-nf-param-name tz))

(defn cmd-startup-pg30 [conn msg-in]
  (let [{:keys [server]} conn
        {:keys [server-state]} server]

    ;; send auth-ok to client
    (cmd-write-msg conn msg-auth {:result 0})

    (let [default-server-params (-> (:parameters @server-state)
                                    (update-keys str/lower-case))
          startup-parameters-from-client (-> (read-startup-parameters msg-in)
                                             (update-keys str/lower-case))]

      (doseq [[k v] (merge default-server-params
                           startup-parameters-from-client)]
        ;;again explicit handing of timezone
        (if (= time-zone-nf-param-name k)
          (set-time-zone conn v)
          (set-session-parameter conn k v))))

    ;; backend key data (used to identify conn for cancellation)
    (cmd-write-msg conn msg-backend-key-data {:process-id (:cid conn), :secret-key 0})
    (cmd-send-ready conn)))

(defn cmd-cancel
  "Tells the connection to stop doing what its doing and return to idle"
  [conn]
  ;; we might this want to be conditional on a 'working state' to avoid races (if you fire loads of cancels randomly), not sure whether
  ;; to use status instead
  ;;TODO need to interrupt the thread belonging to the conn
  (swap! (:conn-state conn) assoc :cancel true)
  nil)

(defn cmd-startup-cancel [conn msg-in]
  (let [{:keys [process-id]} ((:read io-cancel-request) msg-in)
        {:keys [server]} conn
        {:keys [server-state]} server
        {:keys [connections]} @server-state

        cancel-target (get connections process-id)]

    (when cancel-target (cmd-cancel cancel-target))

    (cmd-terminate conn)))

(defn cmd-startup-err [conn err]
  (cmd-send-error conn err)
  (cmd-terminate conn))

(defn- upgrade-conn-to-ssl [{:keys [^Socket socket, ^DataOutputStream out], {:keys [^SSLContext ssl-ctx]} :server, :as conn}]
  (if (and ssl-ctx (not (instance? SSLSocket socket)))
    ;; upgrade the socket, then wait for the client's next startup message

    (do
      (log/trace "upgrading to SSL")

      (.writeByte out (byte \S))

      (let [^SSLSocket ssl-socket (-> (.getSocketFactory ssl-ctx)
                                      (.createSocket socket
                                                     (-> (.getInetAddress socket)
                                                         (.getHostAddress))
                                                     (.getPort socket)
                                                     true))]
        (try
          (.setUseClientMode ssl-socket false)
          (.startHandshake ssl-socket)
          (log/trace "SSL handshake successful")
          (catch Exception e
            (log/debug e "error in SSL handshake")
            (throw e)))

        (assoc conn
               :socket ssl-socket
               :in (DataInputStream. (.getInputStream ssl-socket))
               :out (DataOutputStream. (.getOutputStream ssl-socket)))))

    ;; unsupported - recur and give the client another chance to say hi
    (do
      (.writeByte out (byte \N))
      conn)))

(defn cmd-startup [conn]
  (loop [{:keys [in] :as conn} conn]
    (let [{:keys [version msg-in]} (read-version in)]
      (case version
        :gssenc (doto conn
                  (cmd-startup-err (err-protocol-violation "GSSAPI is not supported")))

        :ssl (recur (upgrade-conn-to-ssl conn))

        :cancel (doto conn
                  (cmd-startup-cancel msg-in))

        :30 (doto conn
              (cmd-startup-pg30 msg-in))

        (doto conn
          (cmd-startup-err (err-protocol-violation "Unknown protocol version")))))))

(defn cmd-enqueue-cmd
  "Enqueues another command for execution later (puts it at the back of the :cmd-buf queue).

  Commands can be queued with (non-conn) args using vectors like so (enqueue-cmd conn [#'cmd-simple-query {:query some-sql}])"
  [{:keys [conn-state]} & cmds]
  (swap! conn-state update :cmd-buf (fnil into PersistentQueue/EMPTY) cmds))

(def json-bytes (comp types/utf8 json/json-str json-clj))

(defn write-json [_env ^IVectorReader rdr idx]
  (json-bytes (.getObject rdr idx)))

(defn cmd-send-query-result [{:keys [!closing?, conn-state] :as conn}
                             {:keys [query, ^IResultCursor result-cursor fields]}]
  (let [;; this query has been cancelled!
        cancelled-by-client? #(:cancel @conn-state)
        ;; please die as soon as possible (not the same as draining, which leaves conns :running for a time)
        n-rows-out (volatile! 0)
        session (:session @conn-state)]

    (.forEachRemaining
     result-cursor
     (reify Consumer
       (accept [_ rel]

         (cond
           (cancelled-by-client?)
           (do (log/trace "Query cancelled by client")
               (swap! conn-state dissoc :cancel)
               (cmd-send-error conn (err-query-cancelled "query cancelled during execution")))

           (Thread/interrupted)
           (do
             (log/trace "query interrupted by server (forced shutdown)")
             (throw (InterruptedException.)))

           @!closing? (log/trace "query result stream stopping (conn closing)")

           :else
           (try
             (dotimes [idx (.rowCount ^RelationReader rel)]
               (let [row (map
                          (fn [{:keys [field-name write-binary write-text result-format]}]
                            (let [rdr (.readerForName ^RelationReader rel field-name)]
                              (when-not (.isNull rdr idx)
                                (if (= :binary result-format)
                                  (write-binary session rdr idx)
                                  (if write-text
                                    (write-text session rdr idx)
                                    (write-json session rdr idx))))))
                          fields)]
                 (cmd-write-msg conn msg-data-row {:vals row})
                 (vswap! n-rows-out inc)))

             ;; allow interrupts - this can happen if we are blocking during the row reduce and our conn is forced to close.
             (catch InterruptedException e
               (log/trace e "Interrupt thrown writing query results out")
               (throw e))

             ;; rethrow socket ex without logs (this is expected during any msg transfer
             ;; might later need to be more specific for storage
             ;; no point sending an error msg, the conn is probably dead.
             (catch SocketException e (throw e))

             ;; consider io error msg from storage (e.g AWS policy limit reached?)
             ;; not worried now, long term may sit elsewhere, but maybe not.

             ;; (ideally) unexpected (e.g bug in operator)
             (catch Throwable e
               (log/error e "An exception was caught during query result set iteration")
               (cmd-send-error conn (err-internal "unexpected server error during query execution"))))))))

      (cmd-write-msg conn msg-command-complete {:command (str (statement-head query) " " @n-rows-out)})))

(defn- close-result-cursor [conn ^IResultCursor result-cursor]
  (try
    (.close result-cursor)
    (catch Throwable e
      (log/fatal e "Exception caught closing result cursor, resources may have been leaked - please restart XTDB")
      (.close ^Closeable conn))))

(def supported-param-oids
  (set (map :oid (vals types/pg-types))))

(defn- execute-tx [{:keys [node]} dml-buf tx-opts]
  (let [tx-ops (mapv (fn [{:keys [transformed-query params]}]
                       [:sql transformed-query params])
                     dml-buf)]
    (try
      (xt/execute-tx node tx-ops tx-opts)
      (catch IllegalArgumentException e (throw e))
      (catch RuntimeException e (throw e))
      (catch Throwable e
        (log/debug e "Error on execute-tx")
        (err-pg-exception e "unexpected error on tx submit (report as a bug)")))))

(defn- ->xtify-param [session {:keys [param-format param-fields]}]
  (fn xtify-param [param-idx param]
    (when-not (nil? param)
      (let [param-oid (:oid (nth param-fields param-idx))
            param-format (nth param-format param-idx nil)
            param-format (or param-format (nth param-format param-idx :text))
            mapping (get types/pg-types-by-oid param-oid)
            _ (when-not mapping (throw (Exception. "Unsupported param type provided for read")))
            {:keys [read-binary, read-text]} mapping]
        (if (= :binary param-format)
          (read-binary session param)
          (read-text session param))))))

(defn- cmd-exec-dml [{:keys [conn-state] :as conn} {:keys [dml-type query transformed-query params param-fields] :as stmt}]
  (if (or (not= (count param-fields) (count params))
          (some #(= 0 (:oid %)) param-fields))
    (do (log/error "Missing types for params in DML statement")
        (cmd-send-error
         conn
         (err-protocol-violation "Missing types for params - Client must specify types for all params in DML statements")))
    (let [{:keys [session transaction]} @conn-state
          ^Clock clock (:clock session)
          xtify-param (->xtify-param session stmt)
          xt-params (vec (map-indexed xtify-param params))

          stmt {:query query,
                :transformed-query transformed-query
                :params xt-params}

          cmd-complete-msg {:command (case dml-type
                                        ;; insert <oid> <rows>
                                        ;; oid is always 0 these days, its legacy thing in the pg protocol
                                        ;; rows is 0 for us cus async
                                        :insert "INSERT 0 0"
                                        ;; otherwise head <rows>
                                        :delete "DELETE 0"
                                        :update "UPDATE 0"
                                        :erase "ERASE 0"
                                        :assert "ASSERT")}]

      (if transaction
        ;; we buffer the statement in the transaction (to be flushed with COMMIT)
        (do
          (swap! conn-state update-in [:transaction :dml-buf] (fnil conj []) stmt)
          (cmd-write-msg conn msg-command-complete cmd-complete-msg))

        (let [{:keys [error] :as tx-res} (execute-tx conn [stmt] {:default-tz (.getZone clock)})]
          (if error
            (cmd-send-error conn (err-protocol-violation (ex-message error)))
            (do
              (cmd-write-msg conn msg-command-complete cmd-complete-msg)
              (swap! conn-state assoc :latest-submitted-tx tx-res))))))))

(defn cmd-exec-query
  "Given a statement of type :query will execute it against the servers :node and send the results."
  [conn
   {:keys [bound-query] :as portal}]
  (let [result-cursor
        (try
          (.openCursor ^BoundQuery bound-query)
          (catch InterruptedException e
            (log/trace e "Interrupt thrown opening result cursor")
            (throw e))
          (catch Throwable e
            (log/error e)
            (cmd-send-error conn (err-pg-exception e "unexpected server error opening cursor for portal"))
            :failed-to-open-cursor))]

    (when-not (= result-cursor :failed-to-open-cursor)
      (try
        (cmd-send-query-result conn (assoc portal :result-cursor result-cursor))
        (catch InterruptedException e
          (log/trace e "Interrupt thrown sending query results")
          (throw e))
        (catch Throwable e
          (log/error e)
          (cmd-send-error conn (err-pg-exception e "unexpected server error during query execution")))
        (finally
          ;; try and close the result-cursor (to warn on leak!)
          (close-result-cursor conn result-cursor))))))

(defn- cmd-send-row-description [conn cols]
  (let [defaults {:table-oid 0
                  :column-attribute-number 0
                  :typlen -1
                  :type-modifier -1
                  :result-format :text}
        apply-defaults (fn [col]
                         (merge defaults col))
        data {:columns (mapv apply-defaults cols)}]

    (log/trace "Sending Row Description - " (assoc data :input-cols cols))
    (cmd-write-msg conn msg-row-description data)))

(defn cmd-describe-canned-response [conn canned-response]
  (let [{:keys [cols]} canned-response]
    (cmd-send-row-description conn cols)))

(defn cmd-describe-portal [conn {:keys [fields]}]
  (cmd-send-row-description conn fields))

(defn cmd-send-parameter-description [conn {:keys [param-fields]}]
  (log/trace "Sending Parameter Description - " {:param-fields param-fields})
  (cmd-write-msg conn msg-parameter-description {:parameter-oids (mapv :oid param-fields)}))

(defn cmd-describe-prepared-stmt [conn {:keys [fields] :as stmt}]
  (cmd-send-parameter-description conn stmt)
  (cmd-send-row-description conn fields))

(defn cmd-begin [{:keys [node conn-state] :as conn} tx-opts]
  (swap! conn-state
         (fn [{:keys [session latest-submitted-tx] :as st}]
           (let [{:keys [^Clock clock]} session]
             (-> st
                 (assoc :transaction
                        (-> {:current-time (.instant clock)
                             :at-tx (time/max-tx (:latest-completed-tx (xt/status node))
                                                 latest-submitted-tx)}
                            (into (:characteristics session))
                            (into (:next-transaction session))
                            (into tx-opts)))

                 ;; clear :next-transaction variables for now
                 ;; aware right now this may not be spec compliant depending on interplay between START TRANSACTION and SET TRANSACTION
                 ;; thus TODO check spec for correct 'clear' behaviour of SET TRANSACTION vars
                 (update :session dissoc :next-transaction)))))

  (cmd-write-msg conn msg-command-complete {:command "BEGIN"}))

(defn cmd-commit [{:keys [conn-state] :as conn}]
  (let [{{:keys [failed err dml-buf tx-system-time]} :transaction, {:keys [^Clock clock]} :session} @conn-state]
    (if failed
      (cmd-send-error conn (or err (err-protocol-violation "transaction failed")))

      (try
        (let [{:keys [error] :as tx-res} (execute-tx conn dml-buf {:default-tz (.getZone clock)
                                                                   :system-time tx-system-time})]
          (if error
            (do
              (swap! conn-state (fn [conn-state]
                                  (-> conn-state
                                      (update :transaction assoc :failed true, :err error)
                                      (assoc :latest-submitted-tx tx-res))))
              (cmd-send-error conn (err-protocol-violation (ex-message error))))
            (do
              (swap! conn-state (fn [conn-state]
                                  (-> conn-state
                                      (dissoc :transaction)
                                      (assoc :latest-submitted-tx tx-res))))
              (cmd-write-msg conn msg-command-complete {:command "COMMIT"}))))

        (catch IllegalArgumentException e
          (cmd-send-error conn (err-protocol-violation (ex-message e))))
        (catch RuntimeException e
          (cmd-send-error conn (err-protocol-violation (ex-message e))))))))

(defn cmd-rollback [{:keys [conn-state] :as conn}]
  (swap! conn-state dissoc :transaction)
  (cmd-write-msg conn msg-command-complete {:command "ROLLBACK"}))

(defn cmd-describe
  "Sends description messages (e.g msg-row-description) to the client for a prepared statement or portal."
  [{:keys [conn-state] :as conn} {:keys [describe-type, describe-name]}]
  (let [coll-k (case describe-type
                 :portal :portals
                 :prepared-stmt :prepared-statements
                 (Object.))
        {:keys [statement-type canned-response] :as describe-target} (get-in @conn-state [coll-k describe-name])]

    (case statement-type
      :empty-query (cmd-write-msg conn msg-no-data)
      :canned-response (cmd-describe-canned-response conn canned-response)
      :dml (do (when (= :prepared-stmt describe-type)
                 (cmd-send-parameter-description conn describe-target))
               (cmd-write-msg conn msg-no-data))
      :query (if (= :prepared-stmt describe-type)
               (cmd-describe-prepared-stmt conn describe-target)
               (cmd-describe-portal conn describe-target))
      (cmd-write-msg conn msg-no-data))))

(defn cmd-set-session-parameter [conn parameter value]
  (set-session-parameter conn parameter value)
  (cmd-write-msg conn msg-command-complete {:command "SET"}))

(defn cmd-set-transaction [{:keys [conn-state] :as conn} tx-opts]
  ;; set the access mode for the next transaction
  ;; intention is BEGIN then can take these parameters as a preference to those in the session
  (when tx-opts
    (swap! conn-state update-in [:session :next-transaction] (fnil into {}) tx-opts))

  (cmd-write-msg conn msg-command-complete {:command "SET TRANSACTION"}))

(defn cmd-set-time-zone [conn tz]
  (set-time-zone conn tz)
  (cmd-write-msg conn msg-command-complete {:command "SET TIME ZONE"}))

(defn cmd-set-session-characteristics [{:keys [conn-state] :as conn} session-characteristics]
  (swap! conn-state update-in [:session :characteristics] (fnil into {}) session-characteristics)
  (cmd-write-msg conn msg-command-complete {:command "SET SESSION CHARACTERISTICS"}))

(defn- permissibility-err
  "Returns an error if the given statement, which is otherwise valid - is not permitted (say due to the access mode, transaction state)."
  [{:keys [conn-state]} {:keys [statement-type]}]
  (let [{:keys [transaction]} @conn-state
        {:keys [access-mode]} transaction]
    (cond
      (and (= :set-transaction statement-type) transaction)
      (err-protocol-violation "invalid transaction state -- active SQL-transaction")

      (and (= :begin statement-type) transaction)
      (err-protocol-violation "invalid transaction state -- active SQL-transaction")

      (and (= :dml statement-type) (= :read-only access-mode))
      (err-protocol-violation "DML is not allowed in a READ ONLY transaction")

      (and (= :query statement-type) (= :read-write access-mode))
      (err-protocol-violation "Queries are unsupported in a DML transaction"))))

(defn cmd-sync
  "Sync commands are sent by the client to commit transactions (we do not do anything here yet),
  and to clear the error state of a :extended mode series of commands (e.g the parse/bind/execute dance)"
  [{:keys [conn-state] :as conn}]
  ;; TODO commit / rollback should be used here if not in an explicit tx?

  (when-not (:transaction @conn-state)
    ;;if outside an explicit transaction/transaction block (BEGIN/COMMIT) close any portals
    ;;as these are implicitly closed/cleaned up at the end of the transcation
    (doseq [portal-name (keys (:portals @conn-state))]
      (close-portal conn portal-name)))

  (cmd-send-ready conn)
  (swap! conn-state dissoc :skip-until-sync, :protocol))

(defn cmd-flush
  "Flushes any pending output to the client."
  [conn]
  (.flush ^OutputStream (:out conn)))

(defn resolve-defaulted-params [declared-params inferred-params]
  (let [declared-params (vec declared-params)]
    (map-indexed
     (fn [idx inf-param]
       (if-let [dec-param (nth declared-params idx nil)]
         (if (= :default (:col-type dec-param))
           inf-param
           dec-param)
         inf-param))
     inferred-params)))

(defn parse
  "Responds to a msg-parse message that creates a prepared-statement."
  [{:keys [conn-state cid server ^IXtdbInternal node] :as conn}
   {:keys [stmt-name query arg-types]}]

  (log/trace "Parsing" {:stmt-name stmt-name,
                        :query query
                        :port (:port server)
                        :cid cid
                        :arg-types arg-types})

  (let [{:keys [session latest-submitted-tx]} @conn-state
        {:keys [^Clock clock], {:strs [fallback_output_format]} :parameters} session
        {:keys [err statement-type] :as stmt} (interpret-sql query {:default-tz (.getZone ^Clock (get-in @conn-state [:session :clock]))
                                                                    :latest-submitted-tx latest-submitted-tx})
        unsupported-arg-types (remove supported-param-oids arg-types)
        stmt (when-not err (assoc stmt :arg-types arg-types))
        err (or err
                (when-some [oid (first unsupported-arg-types)]
                  (err-protocol-violation (format "parameter type oid(%s) currently unsupported by xt" oid)))
                (permissibility-err conn stmt))
        param-types (map types/pg-types-by-oid arg-types)]

    (if err
      (do
        (log/debug "Error parsing query: " err)
        (cmd-send-error conn err))

      (-> (if (= :query statement-type)
            (try
              (let [param-col-types (mapv :col-type param-types)]
                (if (some nil? param-col-types)
                  (cmd-send-error conn
                                  (err-protocol-violation (str "Unsupported param-types in query: "
                                                               (pr-str (->> param-types
                                                                            (into [] (comp (filter (comp nil? :col-type))
                                                                                           (map :typname)
                                                                                           (distinct))))))))

                  (let [{:keys [ra-plan, ^String transformed-query]} stmt
                        query-opts {:after-tx latest-submitted-tx
                                    :tx-timeout (Duration/ofSeconds 1)
                                    :param-types param-col-types
                                    :default-tz (.getZone clock)}

                        ^PreparedQuery pq (if ra-plan
                                            (.prepareRaQuery node ra-plan query-opts)
                                            (.prepareQuery node ^String transformed-query query-opts))]
                    (when-let [warnings (.warnings pq)]
                      (doseq [warning warnings]
                        (cmd-send-notice conn (notice-warning (plan/error-string warning)))))

                    {:prepared-stmt (assoc stmt
                                           :prepared-stmt pq
                                           :fields (mapv (partial types/field->pg-type fallback_output_format) (.columnFields pq))
                                           :param-fields (->> (.paramFields pq)
                                                              (map types/field->pg-type)
                                                              (map #(set/rename-keys % {:column-oid :oid}))
                                                              (resolve-defaulted-params param-types)))
                     :prep-outcome :success})))

              (catch InterruptedException e
                (log/trace e "Interrupt thrown compiling query")
                (throw e))
              (catch Throwable e
                (log/error e)
                (cmd-send-error conn (err-pg-exception e "unexpected server error compiling query"))))

            {:prepared-stmt (assoc stmt :param-fields param-types)
             ;; NOTE this means that for DML statments we assume the number and type of params is exactly
             ;; those specified by the client in arg-types, irrelevant of the number featured in the query string.
             ;; If a client subsequently binds a different number of params we will send an error msg
             :prep-outcome :success})

          (assoc :stmt-name stmt-name
                 :statement-type statement-type)))))

(defn cmd-parse
  "Responds to a msg-parse message that creates a prepared-statement."
  [{:keys [conn-state] :as conn} msg-data]
  (swap! conn-state assoc :protocol :extended)
  (let [{:keys [prepared-stmt statement-type prep-outcome stmt-name]} (parse conn msg-data)]
    (when (= :success prep-outcome)
      (swap! conn-state (fn [{:keys [transaction] :as conn-state}]
                          (let [access-mode (when transaction
                                              (case statement-type
                                                :query :read-only
                                                :dml :read-write
                                                nil))]
                            (-> conn-state
                                (assoc-in [:prepared-statements stmt-name] prepared-stmt)
                                (cond-> access-mode (assoc-in [:transaction :access-mode] access-mode))))))
      (cmd-write-msg conn msg-parse-complete))))

(defn with-result-formats [pg-types result-format]
  (when-let [result-formats (let [type-count (count pg-types)]
                              (cond
                                (empty? result-format) (repeat type-count :text)
                                (= 1 (count result-format)) (repeat type-count (first result-format))
                                (= (count result-format) type-count) result-format))]
    (mapv (fn [pg-type result-format]
            (assoc pg-type :result-format result-format))
          pg-types
          result-formats)))

(defn cmd-bind [{:keys [conn-state] :as conn}
                {:keys [portal-name stmt-name params result-format] :as bind-msg}]
  (if-let [{:keys [statement-type] :as stmt} (get-in @conn-state [:prepared-statements stmt-name])]
    (let [;; add data from bind-msg to stmt, for queries params are bound now, for dml this happens later during execute-tx.
          {:keys [prepared-stmt] :as stmt-with-bind-msg} (merge stmt bind-msg)

          ;;if statement is a query, bind it, else use the statement as a portal
          {:keys [portal bind-outcome]} (if (= :query statement-type)
                                          (let [{:keys [session transaction]} @conn-state
                                                {:keys [^Clock clock], {:strs [fallback_output_format]} :parameters} session

                                                xtify-param (->xtify-param session stmt-with-bind-msg)
                                                xt-params (vec (map-indexed xtify-param params))

                                                query-opts {:basis {:at-tx (or (:at-tx stmt) (:at-tx transaction))
                                                                    :current-time (or (:current-time stmt)
                                                                                      (:current-time transaction)
                                                                                      (.instant clock))}
                                                            :default-tz (.getZone clock)
                                                            :args xt-params}]

                                            (try
                                              (let [^BoundQuery bound-query (.bind ^PreparedQuery prepared-stmt query-opts)]
                                                (if-let [fields (-> (map (partial types/field->pg-type fallback_output_format) (.columnFields bound-query))
                                                                    (with-result-formats result-format))]
                                                  {:portal (assoc stmt-with-bind-msg
                                                                  :bound-query bound-query
                                                                  :fields fields)
                                                   :bind-outcome :success}

                                                  (cmd-send-error conn (err-protocol-violation "invalid result format"))))

                                              (catch InterruptedException e
                                                (log/trace e "Interrupt thrown binding prepared statement")
                                                (throw e))
                                              (catch Throwable e
                                                (log/error e)
                                                (cmd-send-error conn (err-pg-exception (.getCause e) "unexpected server error binding prepared statement")))))

                                          {:portal stmt-with-bind-msg
                                           :bind-outcome :success})]

      ;; add the portal
      (when (= :success bind-outcome)
        (swap! conn-state assoc-in [:portals portal-name] portal))

      ;; track the portal name to the prepared stmt (for close)
      (when (= :success bind-outcome)
        (swap! conn-state update-in [:prepared-statements stmt-name :portals] (fnil conj #{}) portal-name))

      (if (and (= :success bind-outcome) (= :extended (:protocol @conn-state)))
        (cmd-write-msg conn msg-bind-complete)
        bind-outcome))

    (cmd-send-error conn (err-protocol-violation "no prepared statement"))))

(defn cmd-execute
  "Handles a msg-execute to run a previously bound portal (via msg-bind)."
  [{:keys [conn-state] :as conn}
   {:keys [portal-name _limit]}]
  ;;TODO implement limit for queries that return rows
  (if-some [{:keys [statement-type canned-response parameter tz value session-characteristics tx-characteristics] :as portal}
            (get-in @conn-state [:portals portal-name])]

    (case statement-type
      :empty-query (cmd-write-msg conn msg-empty-query)
      :canned-response (cmd-write-canned-response conn canned-response)
      :set-session-parameter (cmd-set-session-parameter conn parameter value)
      :set-session-characteristics (cmd-set-session-characteristics conn session-characteristics)
      :set-transaction (cmd-set-transaction conn tx-characteristics)
      :set-time-zone (cmd-set-time-zone conn tz)
      :ignore (cmd-write-msg conn msg-command-complete {:command "IGNORED"})
      :begin (cmd-begin conn tx-characteristics)
      :rollback (cmd-rollback conn)
      :commit (cmd-commit conn)
      :query (cmd-exec-query conn portal)
      :dml (cmd-exec-dml conn portal)

      (throw (UnsupportedOperationException. (pr-str {:portal portal}))))
    (cmd-send-error conn (err-protocol-violation "no such portal"))))

(defn cmd-simple-query [{:keys [conn-state] :as conn} {:keys [query]}]
  ;;TODO I think it would be technically better if simple-query avoided
  ;;registering stmts and portals in the conn-stat and instead called the
  ;;various query statges directly, passing any state directly, to
  ;;remove the chance that simple-query side effects/resources can be
  ;;addressed by extended cmds/msgs.
  (swap! conn-state assoc :protocol :simple)

  (let [portal-name ""
        {:keys [prepared-stmt prep-outcome stmt-name]} (parse conn {:query query :stmt-name ""})]

    (when (= :success prep-outcome)
      (swap! conn-state assoc-in [:prepared-statements stmt-name] prepared-stmt)

      (when (= :success (cmd-bind conn {:portal-name portal-name
                                        :stmt-name stmt-name}))

        (when (#{:query :canned-response} (:statement-type prepared-stmt))
          ;; Client only expects to see a RowDescription (result of cmd-descibe)
          ;; for certain statement types
          (cmd-describe conn {:describe-type :portal
                              :describe-name portal-name}))

        (cmd-execute conn {:portal-name portal-name})
        (close-portal conn portal-name))

       ;;close/remove stmt
      (swap! conn-state update :prepared-statements dissoc stmt-name)))

  (cmd-send-ready conn))

(defn- handle-msg [{:keys [msg-char8, msg-in]} {:keys [cid, conn-state] :as conn}]
  (try
    (let [msg-var (client-msgs msg-char8)]

      (log/trace "Read client msg"
                 {:cid cid, :msg (or msg-var msg-char8), :char8 msg-char8})

      (when (:skip-until-sync @conn-state)
        (if (= msg-var #'msg-sync)
          (cmd-enqueue-cmd conn [#'cmd-sync])
          (log/trace "Skipping msg until next sync due to error in extended protocol"
                     {:cid cid, :msg (or msg-var msg-char8), :char8 msg-char8})))

      (when-not msg-var
        (cmd-send-error conn (err-protocol-violation "unknown client message")))

      (when (and msg-var (not (:skip-until-sync @conn-state)))
        (let [msg-data ((:read @msg-var) msg-in)]
          (condp = msg-var
            #'msg-simple-query (cmd-simple-query conn msg-data)
            #'msg-terminate (cmd-terminate conn)
            #'msg-close (cmd-close conn msg-data)
            #'msg-parse (cmd-parse conn msg-data)
            #'msg-bind (cmd-bind conn msg-data)
            #'msg-sync (cmd-sync conn)
            #'msg-execute (cmd-execute conn msg-data)
            #'msg-describe (cmd-describe conn msg-data)
            #'msg-flush (cmd-flush conn)

            ;; ignored by xt
            #'msg-password nil

            (cmd-send-error conn (err-protocol-violation "unknown client message"))))))
    (catch InterruptedException e (throw e))
    (catch Throwable e (log/error e "Uncaught exception in handle-msg"))))

;; connection loop
;; we run a blocking io server so a connection is simple a loop sitting on some thread
(defn- conn-loop [{:keys [cid, server, ^Socket socket, in, conn-state], !conn-closing? :!closing?, :as conn}]
  (let [{:keys [port], !server-closing? :!closing?} server]
    (loop []
      (cond
        ;; the connection is closing right now
        ;; let it close.
        @!conn-closing?
        (log/trace "Connection loop exiting (closing)" {:port port, :cid cid})

        ;; if the server is closing, we may later allow connections to finish their queries
        ;; (consider policy - server has a drain limit but maybe better to be explicit here as well)
        (and @!server-closing?
             (not= :extended (:protocol @conn-state))
             ;; for now we allow buffered commands to be run
             ;; before closing - there probably needs to be limits to this
             ;; (for huge queries / result sets)
             (empty? (:cmd-buf @conn-state)))
        (do (log/trace "Connection loop exiting (draining)" {:port port, :cid cid})
            ;; TODO I think I should send an error, but if I do it causes a crash on the client?
            #_(cmd-send-error conn (err-admin-shutdown "draining connections"))
            (reset! !conn-closing? true))

        ;; well, it won't have been us, as we would drain first
        (.isClosed socket)
        (do (log/trace "Connection closed unexpectedly" {:port port, :cid cid})
            (reset! !conn-closing? true))

        ;; we have queued a command to be processed
        ;; a command represents something we want the server to do for the client
        ;; such as error, or send data.
        (seq (:cmd-buf @conn-state))
        (let [cmd-buf (:cmd-buf @conn-state)
              [cmd-fn & cmd-args] (peek cmd-buf)]
          (if (seq cmd-buf)
            (swap! conn-state assoc :cmd-buf (pop cmd-buf))
            (swap! conn-state dissoc :cmd-buf))

          (when cmd-fn (apply cmd-fn conn cmd-args))

          (recur))

        ;; go idle until we receive another msg from the client
        :else
        (do
          (handle-msg (read-typed-msg in) conn)
          (recur))))))

(defn- ->tmp-node [{:keys [^Map !tmp-nodes]} conn-state]
  (.computeIfAbsent !tmp-nodes (get-in conn-state [:session :parameters "database"] "xtdb")
                    (fn [db]
                      (log/debug "starting tmp-node" (pr-str db))
                      (xtn/start-node {:server nil}))))

(defn- connect
  "Starts and runs a connection on the current thread until it closes.

  The connection exiting for any reason (either because the connection, received a close signal, or the server is draining, or unexpected error) should result in connection resources being
  freed at the end of this function. So the connections lifecycle should be totally enclosed over the lifetime of a connect call.

  See comment 'Connection lifecycle'."
  [{:keys [server-state, port, node] :as server} ^Socket conn-socket]
  (let [close-promise (promise)
        {:keys [cid] :as conn} (util/with-close-on-catch [_ conn-socket]
                                 (let [cid (:next-cid (swap! server-state update :next-cid (fnil inc 0)))
                                       !conn-state (atom {:close-promise close-promise
                                                          :session {:access-mode :read-only
                                                                    :clock (:clock @server-state)}})]
                                   (try
                                     (-> (map->Connection {:cid cid,
                                                           :server server,
                                                           :socket conn-socket,
                                                           :node node
                                                           :in (DataInputStream. (.getInputStream conn-socket)),
                                                           :out (DataOutputStream. (.getOutputStream conn-socket))
                                                           :!closing? (atom false)
                                                           :conn-state !conn-state})
                                         (cmd-startup)
                                         (cond-> (nil? node) (assoc :node (->tmp-node server @!conn-state))))
                                     (catch Throwable t
                                       (log/warn t "error on conn startup")))))]

    (try
      (swap! server-state assoc-in [:connections cid] conn)

      (log/trace "Starting connection loop" {:port port, :cid cid})
      (conn-loop conn)
      (catch SocketException e
        (when (= "Broken pipe (Write failed)" (.getMessage e))
          (log/trace "Client closed socket while we were writing" {:port port, :cid cid})
          (.close conn-socket))

        (when (= "Connection reset by peer" (.getMessage e))
          (log/trace "Client closed socket while we were writing" {:port port, :cid cid})
          (.close conn-socket))

        ;; socket being closed is normal, otherwise log.
        (when-not (.isClosed conn-socket)
          (log/error e "An exception was caught during connection" {:port port, :cid cid})))

      (catch EOFException _
        (log/trace "Connection closed by client" {:port port, :cid cid}))

      (catch IOException e
        (log/debug e "IOException in connection" {:port port, :cid cid}))

      (catch InterruptedException _)

      (finally
        (util/close conn)

        ;; can be used to co-ordinate waiting for close
        (when-not (realized? close-promise)
          (deliver close-promise true))))))

(defn- accept-loop
  "Runs an accept loop on the current thread (intended to be the Server's :accept-thread).

  While the server is running, tries to .accept connections on its :accept-socket. Once accepted,
  a connection is created on the servers :thread-pool via the (connect) function."
  [{:keys [^ServerSocket accept-socket,
           ^ExecutorService thread-pool]
    :as server}]

  (try
    (loop []
      (cond
        (Thread/interrupted) (throw (InterruptedException.))

        (.isClosed accept-socket)
        (log/trace "Accept socket closed, exiting accept loop")

        :else
        (do
          (try
            ;; accept next connection (blocks until interrupt or close)
            (let [conn-socket (.accept accept-socket)]
              (.setTcpNoDelay conn-socket true)
              ;; TODO fix buffer on tp? q gonna be infinite right now
              (.submit thread-pool ^Runnable (fn [] (connect server conn-socket))))
            (catch SocketException e
              (when (and (not (.isClosed accept-socket))
                         (not= "Socket closed" (.getMessage e)))
                (log/warn e "Accept socket exception")))
            (catch IOException e
              (log/warn e "Accept IO exception")))
          (recur))))

    (catch InterruptedException _)

    (finally
      (util/try-close accept-socket)))

  (log/trace "exiting accept loop"))

(defn serve
  "Creates and starts a PostgreSQL wire-compatible server.

  node: if provided, uses the given node for all connections; otherwise, creates a transient, in-memory node for each new connection

  Options:

  :port (default 5432). Provide '0' to open a socket on an unused port.
  :num-threads (bounds the number of client connections, default 42)
  "
  ([node] (serve node {}))
  ([node {:keys [port num-threads drain-wait ssl-ctx]
          :or {port 5432
               num-threads 42
               drain-wait 5000}}]
   (util/with-close-on-catch [accept-socket (ServerSocket. port)]
     (let [port (.getLocalPort accept-socket)
           server (map->Server {:node node
                                :port port
                                :accept-socket accept-socket
                                :thread-pool (Executors/newFixedThreadPool num-threads (util/->prefix-thread-factory "pgwire-connection-"))
                                :!closing? (atom false)
                                ;;TODO use clock from node or at least take clock as a param for testing
                                :server-state (atom (let [default-clock (Clock/systemDefaultZone)]
                                                      {:clock default-clock
                                                       :drain-wait drain-wait
                                                       :parameters {"server_version" expr/postgres-server-version
                                                                    "server_encoding" "UTF8"
                                                                    "client_encoding" "UTF8"
                                                                    "DateStyle" "ISO"
                                                                    "IntervalStyle" "ISO_8601"
                                                                    "TimeZone" (str (.getZone ^Clock default-clock))
                                                                    "integer_datetimes" "on"}}))
                                :ssl-ctx ssl-ctx
                                :!tmp-nodes (when-not node
                                              (ConcurrentHashMap.))})

           accept-thread (-> (Thread/ofVirtual)
                             (.name (str "pgwire-server-accept-" port))
                             (.uncaughtExceptionHandler util/uncaught-exception-handler)
                             (.unstarted (fn []
                                           (accept-loop server))))

           server (assoc server :accept-thread accept-thread)]

       (.start accept-thread)
       server))))

(defmethod xtn/apply-config! ::server [^Xtdb$Config config, _ {:keys [port num-threads ssl] :as server}]
  (if server
    (cond-> (.getServer config)
      (some? port) (.port port)
      (some? num-threads) (.numThreads num-threads)
      (some? ssl) (.ssl (util/->path (:keystore ssl)) (:keystore-password ssl)))

    (.setServer config nil)))

(defn- ->ssl-ctx [^Path ks-path, ^String ks-password]
  (let [ks-password (.toCharArray ks-password)
        ks (with-open [ks-file (util/open-input-stream ks-path)]
             (doto (KeyStore/getInstance "JKS")
               (.load ks-file ks-password)))
        kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks ks-password))]
    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) nil nil))))

(defn- <-config [^ServerConfig config]
  {:port (.getPort config)
   :num-threads (.getNumThreads config)
   :ssl-ctx (when-let [ssl (.getSsl config)]
              (->ssl-ctx (.getKeyStore ssl) (.getKeyStorePassword ssl)))})

(defmethod ig/prep-key ::server [_ config]
  (into {:node (ig/ref :xtdb/node)}
        (<-config config)))

(defmethod ig/init-key ::server [_ {:keys [node] :as opts}]
  (let [{:keys [port] :as srv} (serve node opts)]
    (log/info (if node "Server" "Playground") "started on port:" port)
    srv))

(defmethod ig/halt-key! ::server [_ srv]
  (util/close srv))

(defn open-playground
  (^xtdb.pgwire.Server [] (open-playground nil))

  (^xtdb.pgwire.Server [{:keys [port], :or {port 0}}]
   (ig/init-key ::server (<-config (doto (ServerConfig.)
                                     (.port port))))))

(defn transit->pgobject [v]
  (doto (PGobject.)
    (.setType "transit")
    (.setValue (String. (serde/write-transit v :json)))))
