(ns xtdb.pgwire
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.antlr :as antlr]
            [xtdb.api :as xt]
            [xtdb.expression :as expr]
            [xtdb.node :as xtn]
            [xtdb.node.impl]
            [xtdb.query]
            [xtdb.sql.plan :as plan]
            [xtdb.time :as time]
            [xtdb.types :as types]
            [xtdb.util :as util])
  (:import [clojure.lang MapEntry]
           [java.io ByteArrayInputStream ByteArrayOutputStream Closeable DataInputStream DataOutputStream EOFException IOException InputStream OutputStream PushbackInputStream]
           [java.lang AutoCloseable Thread$State]
           [java.net ServerSocket Socket SocketException]
           [java.nio.charset StandardCharsets]
           [java.nio.file Path]
           [java.security KeyStore]
           [java.time Clock Duration LocalDate LocalDateTime LocalTime OffsetDateTime Period ZoneId ZonedDateTime]
           [java.util List Map Set]
           [java.util.concurrent ConcurrentHashMap ExecutorService Executors TimeUnit]
           [javax.net.ssl KeyManagerFactory SSLContext SSLSocket]
           (org.antlr.v4.runtime ParserRuleContext)
           [org.apache.arrow.vector PeriodDuration]
           (xtdb.antlr Sql$DirectlyExecutableStatementContext SqlVisitor)
           [xtdb.api ServerConfig Xtdb$Config]
           xtdb.api.module.XtdbModule
           xtdb.node.impl.IXtdbInternal
           (xtdb.query BoundQuery PreparedQuery)
           [xtdb.types IntervalDayTime IntervalMonthDayNano IntervalYearMonth]
           [xtdb.vector IVectorReader RelationReader]))

;; references
;; https://www.postgresql.org/docs/current/protocol-flow.html
;; https://www.postgresql.org/docs/current/protocol-message-formats.html

(defrecord Server [port

                   ^ServerSocket accept-socket
                   ^Thread accept-thread

                   ^ExecutorService thread-pool

                   !closing?

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
                (empty? (:connections @server-state)) nil

                (Thread/interrupted) (log/warn "Interrupted during drain, force closing")

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

(defprotocol Frontend
  (send-client-msg!
    [frontend msg-def]
    [frontend msg-def data])

  (upgrade-to-ssl [frontend ssl-ctx])

  (flush! [frontend]))

(defn- cmd-write-msg
  "Writes out a single message given a definition (msg-def) and optional data record."
  ([{:keys [frontend]} msg-def]
   (send-client-msg! frontend msg-def))

  ([{:keys [frontend]} msg-def data]
   (send-client-msg! frontend msg-def data)))

(declare ->socket-frontend)

(defrecord SocketFrontend [^Socket socket, ^DataInputStream in, ^DataOutputStream out]
  Frontend
  (send-client-msg! [_ msg-def]
    (log/trace "Writing server message" (select-keys msg-def [:char8 :name]))

    (.writeByte out (byte (:char8 msg-def)))
    (.writeInt out 4))

  (send-client-msg! [_ msg-def data]
    (log/trace "Writing server message (with body)" (select-keys msg-def [:char8 :name]))
    (let [bytes-out (ByteArrayOutputStream.)
          msg-out (DataOutputStream. bytes-out)
          _ ((:write msg-def) msg-out data)
          arr (.toByteArray bytes-out)]
      (.writeByte out (byte (:char8 msg-def)))
      (.writeInt out (+ 4 (alength arr)))
      (.write out arr)))

  (upgrade-to-ssl [this ssl-ctx]
    (if (and ssl-ctx (not (instance? SSLSocket socket)))
      ;; upgrade the socket, then wait for the client's next startup message

      (do
        (log/trace "upgrading to SSL")

        (.writeByte out (byte \S))

        (let [^SSLSocket ssl-socket (-> (.getSocketFactory ^SSLContext ssl-ctx)
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

          (->socket-frontend ssl-socket)))

      ;; unsupported - recur and give the client another chance to say hi
      (do
        (.writeByte out (byte \N))
        this)))

  (flush! [_] (.flush out))

  AutoCloseable
  (close [_]
    (when-not (.isClosed socket)
      (util/try-close socket))))

(defn ->socket-frontend [^Socket socket]
  (->SocketFrontend socket
                    (DataInputStream. (.getInputStream socket))
                    (DataOutputStream. (.getOutputStream socket))))

(defrecord Connection [^Server server, frontend, node

                       ;; a positive integer that identifies the connection on this server
                       ;; we will use this as the pg Process ID for messages that require it (such as cancellation)
                       cid

                       !closing? conn-state]

  Closeable
  (close [_]
    (util/close frontend)

    (let [{:keys [server-state]} server]
      (swap! server-state update :connections dissoc cid))

    (log/debug "Connection ended" {:cid cid})))

;; best the server/conn records are opaque when printed as they contain mutual references
(defmethod print-method Server [wtr o] ((get-method print-method Object) wtr o))
(defmethod print-method Connection [wtr o] ((get-method print-method Object) wtr o))

(defmulti handle-msg*
  #_{:clj-kondo/ignore [:unused-binding]}
  (fn [conn {:keys [msg-name] :as msg}]
    msg-name)

  :default ::default)

(defn- read-c-string
  "Postgres strings are null terminated, reads a null terminated utf-8 string from in."
  ^String [^InputStream in]
  (loop [baos (ByteArrayOutputStream.)
         x (.read in)]
    (cond
      (neg? x) (throw (EOFException. "EOF in read-c-string"))
      (zero? x) (String. (.toByteArray baos) StandardCharsets/UTF_8)
      :else (recur (doto baos
                     (.write x))
                   (.read in)))))

(defn- write-c-string
  "Postgres strings are null terminated, writes a null terminated utf8 string to out."
  [^OutputStream out ^String s]
  (.write out (.getBytes s StandardCharsets/UTF_8))
  (.write out 0))

(def ^:private oid-varchar (get-in types/pg-types [:varchar :oid]))
(def ^:private oid-json (get-in types/pg-types [:json :oid]))


;;; errors

(defn- err-protocol-violation [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "08P01"
   :message msg})

(defn- client-err
  ([client-msg] (client-err client-msg nil))
  ([client-msg _data]
   (ex-info client-msg {::client-error (err-protocol-violation client-msg)})))

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

(defn- invalid-text-representation [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "22P02"
   :message msg})

(defn- invalid-binary-representation [msg]
  {:severity "ERROR"
   :localized-severity "ERROR"
   :sql-state "22P03"
   :message msg})

(defn err-pg-exception
  "Returns a pg specific error for an XTDB exception"
  [^Throwable ex generic-msg]
  (if (or (instance? xtdb.IllegalArgumentException ex)
          (instance? xtdb.RuntimeException ex))
    (err-protocol-violation (.getMessage ex))
    (err-internal generic-msg)))

;;; sql processing

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

(defn- interpret-sql [sql {:keys [default-tz latest-submitted-tx-id session-parameters]}]
  (log/debug "Interpreting SQL: " sql)
  (let [sql-trimmed (trim-sql sql)]
    (or (when (str/blank? sql-trimmed)
          {:statement-type :empty-query})

        (when-some [canned-response (get-canned-response sql-trimmed)]
          {:statement-type :canned-response, :canned-response canned-response})

        (try
          (.accept (antlr/parse-statement sql-trimmed)
                   (reify SqlVisitor
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

                     (visitSetRoleStatement [_ _] {:statement-type :set-role})

                     (visitSetTimeZoneStatement [_ ctx]
                       ;; not sure if handlling time zone explicitly is the right approach
                       ;; might be cleaner to handle it like any other session param
                       {:statement-type :set-time-zone
                        :tz (let [v (.getText (.characterString ctx))]
                              (subs v 1 (dec (count v)))) })

                     (visitInsertStmt [this ctx] (-> (.insertStatement ctx) (.accept this)))

                     (visitInsertStatement [_ _]
                       {:statement-type :dml, :dml-type :insert, :query sql})

                     (visitUpdateStmt [this ctx] (-> (.updateStatementSearched ctx) (.accept this)))

                     (visitUpdateStatementSearched [_ _]
                       {:statement-type :dml, :dml-type :update, :query sql})

                     (visitDeleteStmt [this ctx] (-> (.deleteStatementSearched ctx) (.accept this)))

                     (visitDeleteStatementSearched [_ _]
                       {:statement-type :dml, :dml-type :delete, :query sql})

                     (visitEraseStmt [this ctx] (-> (.eraseStatementSearched ctx) (.accept this)))

                     (visitEraseStatementSearched [_ _]
                       {:statement-type :dml, :dml-type :erase, :query sql})

                     (visitAssertStatement [_ _]
                       {:statement-type :dml, :dml-type :assert, :query sql})

                     (visitQueryExpr [this ctx]
                       (let [q {:statement-type :query, :query sql, :parsed-query ctx}]
                         (->> (some-> (.settingQueryVariables ctx) (.settingQueryVariable))
                              (transduce (keep (partial plan/accept-visitor this)) conj q))))

                     ;; handled in plan
                     (visitSettingDefaultValidTime [_ _])
                     (visitSettingDefaultSystemTime [_ _])

                     (visitSettingCurrentTime [_ ctx]
                       [:current-time (time/->instant (.accept (.currentTime ctx) (plan/->ExprPlanVisitor nil nil)))])

                     (visitSettingSnapshotTime [_ ctx]
                       [:snapshot-time (-> (.snapshotTime ctx)
                                           (.accept (plan/->ExprPlanVisitor nil nil))
                                           time/->instant)])

                     (visitShowVariableStatement [_ ctx]
                       {:statement-type :query, :query sql, :parsed-query ctx})

                     (visitShowLatestSubmittedTransactionStatement [_ _]
                       {:statement-type :query, :query sql
                        :ra-plan [:table '[tx_id]
                                  (if latest-submitted-tx-id
                                    [{:tx_id latest-submitted-tx-id}]
                                    [])]})

                     ;; HACK: these values are fixed at prepare-time - if they were to change,
                     ;; and the same prepared statement re-evaluated, the value would be stale.
                     (visitShowSessionVariableStatement [_ ctx]
                       (let [k (session-param-name (.identifier ctx))]
                         {:statement-type :query, :query sql
                          :ra-plan [:table (if-let [v (get session-parameters k)]
                                             [{(keyword k) v}]
                                             [])]}))))

          (catch Exception e
            (throw (ex-info "error parsing sql"
                            {::client-error (err-pg-exception e "error parsing sql")}
                            e)))))))

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
    (instance? LocalTime obj) (str obj)
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

    (or (instance? List obj) (instance? Set obj))
    (mapv json-clj obj)

    ;; maps, cannot be created from SQL yet, but working playground requires them
    ;; we are not dealing with the possibility of non kw/string keys, xt shouldn't return maps like that right now.
    (instance? Map obj) (update-vals obj json-clj)

    (or (instance? xtdb.RuntimeException obj)
        (instance? xtdb.IllegalArgumentException obj))
    (json-clj (-> (ex-data obj)
                  (assoc :message (ex-message obj))))

    (instance? clojure.lang.Keyword obj) (json-clj (str (symbol obj)))
    (instance? clojure.lang.Symbol obj) (json-clj (str (symbol obj)))

    :else
    (throw (Exception. (format "Unexpected type encountered by pgwire (%s)" (class obj))))))


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
  (let [el-rdr (:read io-el)
        el-wtr (:write io-el)]
    {:read (fn read-null-terminated-list [^DataInputStream in]
             (loop [els []]
               (if-let [el (el-rdr in)]
                 (recur (conj els el))
                 (cond->> els
                   (instance? MapEntry (first els)) (into {})))))

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

(def ^:private error-or-notice-type->char
  {:localized-severity \S
   :severity \V
   :sql-state \C
   :message \M
   :detail \D
   :position \P
   :where \W})

(def ^:private char->error-or-notice-type (set/map-invert error-or-notice-type->char))

(def ^:private io-error-notice-field
  "An io-data type that writes a (vector/map-entry pair) k and v as an error field."
  {:read (fn read-error-or-notice-field [^DataInputStream in]
           (let [field-key (char->error-or-notice-type (char (.readByte in)))]
             ;; TODO this might fail if we don't implement some message type
             (when field-key
               (MapEntry/create field-key (read-c-string in)))))
   :write (fn write-error-or-notice-field [^DataOutputStream out [k v]]
            (let [field-char8 (error-or-notice-type->char k)]
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
(def ^:redef server-msgs {})

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
       {:name ~(keyword sym)
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
  :param-oids (io-list io-uint16 io-uint32))

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

(def-msg msg-bind-complete :server \2)

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
  :status {:read (fn [^DataInputStream in]
                   (case (char (.read in))
                     \I :idle
                     \T :transaction
                     \E :failed-transaction
                     :unknown))
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

(def time-zone-nf-param-name "timezone")

(def pg-param-nf->display-format
  {time-zone-nf-param-name "TimeZone"
   "datestyle" "DateStyle"
   "intervalstyle" "IntervalStyle"})

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

(defn- send-ex [conn, ^Throwable e]
  (if-let [client-err (::client-error (ex-data e))]
    (do
      (log/trace "Client error:" (ex-message e) (ex-data e))
      (cmd-send-error conn client-err))

    (log/error e "Uncaught exception processing message")))

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

    (util/close (:bound-query portal))
    (swap! conn-state update-in [:prepared-statements (:stmt-name portal) :portals] disj portal-name)
    (swap! conn-state update :portals dissoc portal-name)))

(defmethod handle-msg* :msg-close [{:keys [conn-state, cid] :as conn} {:keys [close-type, close-name]}]
  ;; Closes a prepared statement or portal that was opened with bind / parse.
  (case close-type
    :prepared-stmt
    (do
      (log/trace "Closing prepared statement" {:cid cid, :stmt close-name})
      (when-some [stmt (get-in @conn-state [:prepared-statements close-name])]

        (doseq [portal-name (:portals stmt)]
          (close-portal conn portal-name))

        (swap! conn-state update :prepared-statements dissoc close-name)))

    :portal
    (close-portal conn close-name)

    nil)

  (cmd-write-msg conn msg-close-complete))

(defmethod handle-msg* :msg-terminate [{:keys [!closing?]} _]
  (reset! !closing? true))

(defn set-time-zone [{:keys [conn-state] :as conn} tz]
  (swap! conn-state update-in [:session :clock] (fn [^Clock clock]
                                                  (.withZone clock (ZoneId/of tz))))
  (set-session-parameter conn time-zone-nf-param-name tz))

(defn cmd-startup-pg30 [conn startup-params]
  (let [{:keys [server]} conn
        {:keys [server-state]} server]

    ;; send auth-ok to client
    (cmd-write-msg conn msg-auth {:result 0})

    (let [default-server-params (-> (:parameters @server-state)
                                    (update-keys str/lower-case))
          startup-parameters-from-client (-> startup-params
                                             (update-keys str/lower-case))]

      (doseq [[k v] (merge default-server-params
                           startup-parameters-from-client)]
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

    (handle-msg* conn {:msg-name :msg-terminate})))

(defn cmd-startup-err [conn err]
  (cmd-send-error conn err)
  (handle-msg* conn {:msg-name :msg-terminate}))

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

;; all postgres client IO arrives as either an untyped (startup) or typed message

(defn- read-untyped-msg [^DataInputStream in]
  (let [size (- (.readInt in) 4)
        barr (byte-array size)
        _ (.readFully in barr)]
    (DataInputStream. (ByteArrayInputStream. barr))))

(defn- read-version [^DataInputStream in]
  (let [^DataInputStream msg-in (read-untyped-msg in)
        version (.readInt msg-in)]
    {:msg-in msg-in
     :version (version-messages version)}))

(defn- read-typed-msg [{{:keys [^DataInputStream in]} :frontend :as _conn}]
  (let [type-char (char (.readUnsignedByte in))
        msg-var (or (client-msgs type-char)
                    (throw (client-err (str "Unknown client message " type-char))))
        rdr (:read @msg-var)]
    (try
      (-> (rdr (read-untyped-msg in))
          (assoc :msg-name (:name @msg-var)))
      (catch Exception e
        (throw (ex-info "error reading client message"
                        {::client-error (err-protocol-violation (str "Error reading client message " (ex-message e)))}
                        e))))))

(defn- read-startup-parameters [^DataInputStream in]
  (loop [in (PushbackInputStream. in)
         acc {}]
    (let [x (.read in)]
      (cond
        (neg? x) (throw (EOFException. "EOF in read-startup-parameters"))
        (zero? x) acc
        :else (do (.unread in (byte x))
                  (recur in (assoc acc (read-c-string in) (read-c-string in))))))))

(defn cmd-startup [conn]
  (loop [{{:keys [in]} :frontend, :keys [server], :as conn} conn]
    (let [{:keys [version msg-in]} (read-version in)]
      (case version
        :gssenc (doto conn
                  (cmd-startup-err (err-protocol-violation "GSSAPI is not supported")))

        :ssl (let [{:keys [^SSLContext ssl-ctx]} server]
               (recur (update conn :frontend upgrade-to-ssl ssl-ctx)))

        :cancel (doto conn
                  (cmd-startup-cancel msg-in))

        :30 (doto conn
              (cmd-startup-pg30 (read-startup-parameters msg-in)))

        (doto conn
          (cmd-startup-err (err-protocol-violation "Unknown protocol version")))))))

(def json-bytes (comp types/utf8 json/json-str json-clj))

(defn write-json [_env ^IVectorReader rdr idx]
  (json-bytes (.getObject rdr idx)))

(def supported-param-oids
  (set (map :oid (vals types/pg-types))))

(defn- execute-tx [{:keys [node] :as conn} dml-buf tx-opts]
  (let [tx-ops (mapv (fn [{:keys [query params]}]
                       [:sql query params])
                     dml-buf)]
    (try
      (xt/execute-tx node tx-ops tx-opts)
      (catch Throwable e
        (log/debug e "Error on execute-tx")
        (cmd-send-error conn (err-pg-exception e "unexpected error on tx submit (report as a bug)"))))))

(defn- ->xtify-param [session {:keys [param-format param-fields]}]
  (fn xtify-param [param-idx param]
    (when (some? param)
      (let [param-oid (:oid (nth param-fields param-idx))
            param-format (or (nth param-format param-idx nil)
                             (nth param-format param-idx :text))
            {:keys [read-binary, read-text]} (or (get types/pg-types-by-oid param-oid)
                                                 (throw (Exception. "Unsupported param type provided for read")))]
        (if (= :binary param-format)
          (read-binary session param)
          (read-text session param))))))

(defn- xtify-params [{:keys [conn-state] :as _conn} params {:keys [param-format] :as stmt}]
  (try
    (vec (map-indexed (->xtify-param (:session @conn-state) stmt) params))
    (catch Exception e
      (throw (ex-info "invalid param representation"
                      {::client-error (if (= param-format :binary)
                                        (invalid-binary-representation (ex-message e))
                                        (invalid-text-representation (ex-message e)))}
                      e)))))

(defn skip-until-sync? [{:keys [conn-state] :as _conn}]
  (:skip-until-sync @conn-state))

(defn- cmd-exec-dml [{:keys [conn-state] :as conn} {:keys [dml-type query params param-fields] :as stmt}]
  (if (or (not= (count param-fields) (count params))
          (some #(= 0 (:oid %)) param-fields))
    (do (log/error "Missing types for params in DML statement")
        (cmd-send-error
         conn
         (err-protocol-violation "Missing types for params - client must specify types for all params in DML statements")))

    (let [{:keys [session transaction]} @conn-state
          ^Clock clock (:clock session)
          xt-params (xtify-params conn params stmt)
          stmt {:query query,
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

      (cond
        (skip-until-sync? conn) nil

        transaction
        ;; we buffer the statement in the transaction (to be flushed with COMMIT)
        (do
          (swap! conn-state update-in [:transaction :dml-buf] (fnil conj []) stmt)
          (cmd-write-msg conn msg-command-complete cmd-complete-msg))

        :else
        (let [{:keys [tx-id error]} (execute-tx conn [stmt] {:default-tz (.getZone clock)})]
          (when-not (skip-until-sync? conn)
            (if error
              (cmd-send-error conn (err-protocol-violation (ex-message error)))
              (cmd-write-msg conn msg-command-complete cmd-complete-msg))
            (swap! conn-state assoc :latest-submitted-tx-id tx-id)))))))

(defn cmd-exec-query [{:keys [conn-state !closing?] :as conn} {:keys [query bound-query fields] :as _portal}]
  (try
    (with-open [result-cursor (.openCursor ^BoundQuery bound-query)]
      (let [cancelled-by-client? #(:cancel @conn-state)
            ;; please die as soon as possible (not the same as draining, which leaves conns :running for a time)

            n-rows-out (volatile! 0)

            session (:session @conn-state)]

        (.forEachRemaining result-cursor
                           (fn [^RelationReader rel]
                             (cond
                               (cancelled-by-client?)
                               (do (log/trace "query cancelled by client")
                                   (swap! conn-state dissoc :cancel)
                                   (cmd-send-error conn (err-query-cancelled "query cancelled during execution")))

                               (Thread/interrupted) (throw (InterruptedException.))

                               @!closing? (log/trace "query result stream stopping (conn closing)")

                               :else (dotimes [idx (.rowCount rel)]
                                       (let [row (mapv
                                                  (fn [{:keys [field-name write-binary write-text result-format]}]
                                                    (let [rdr (.readerForName rel field-name)]
                                                      (when-not (.isNull rdr idx)
                                                        (if (= :binary result-format)
                                                          (write-binary session rdr idx)
                                                          (if write-text
                                                            (write-text session rdr idx)
                                                            (write-json session rdr idx))))))
                                                  fields)]
                                         (cmd-write-msg conn msg-data-row {:vals row})
                                         (vswap! n-rows-out inc))))))

        (cmd-write-msg conn msg-command-complete {:command (str (statement-head query) " " @n-rows-out)})))

    (catch InterruptedException e (throw e))
    (catch Throwable e
      (log/error e)
      (cmd-send-error conn (err-pg-exception e "unexpected server error during query execution")))))

(defn- cmd-send-row-description [conn cols]
  (let [defaults {:table-oid 0
                  :column-attribute-number 0
                  :typlen -1
                  :type-modifier -1
                  :result-format :text}
        apply-defaults (fn [col]
                         (-> (merge defaults col)
                             (select-keys [:column-name :table-oid :column-attribute-number
                                           :column-oid :typlen :type-modifier :result-format])))
        data {:columns (mapv apply-defaults cols)}]

    (log/trace "sending row description - " (assoc data :input-cols cols))
    (cmd-write-msg conn msg-row-description data)))

(defn cmd-describe-canned-response [conn canned-response]
  (let [{:keys [cols]} canned-response]
    (cmd-send-row-description conn cols)))

(defn cmd-describe-portal [conn {:keys [fields]}]
  (cmd-send-row-description conn fields))

(defn cmd-send-parameter-description [conn {:keys [param-fields]}]
  (log/trace "sending parameter description - " {:param-fields param-fields})
  (cmd-write-msg conn msg-parameter-description {:parameter-oids (mapv :oid param-fields)}))

(defn cmd-describe-prepared-stmt [conn {:keys [fields] :as stmt}]
  (cmd-send-parameter-description conn stmt)
  (cmd-send-row-description conn fields))

(defn cmd-begin [{:keys [node conn-state] :as conn} tx-opts]
  (swap! conn-state
         (fn [{:keys [session latest-submitted-tx-id] :as st}]
           (let [{:keys [^Clock clock]} session]
             (-> st
                 (assoc :transaction
                        (-> {:current-time (.instant clock)
                             :snapshot-time (:system-time (:latest-completed-tx (xt/status node)))
                             :after-tx-id (or latest-submitted-tx-id -1)}
                            (into (:characteristics session))
                            (into (:next-transaction session))
                            (into tx-opts)))

                 (update :session dissoc :next-transaction)))))

  (cmd-write-msg conn msg-command-complete {:command "BEGIN"}))

(defn cmd-commit [{:keys [conn-state] :as conn}]
  (let [{{:keys [failed err dml-buf tx-system-time]} :transaction, {:keys [^Clock clock]} :session} @conn-state]
    (if failed
      (cmd-send-error conn (or err (err-protocol-violation "transaction failed")))

      (let [{:keys [tx-id error]} (execute-tx conn dml-buf {:default-tz (.getZone clock)
                                                            :system-time tx-system-time})]
        (cond
          ;; skip - can't use skip-until-sync as we might be in a simple query
          (-> @conn-state :transaction :failed) nil

          error (do
                  (swap! conn-state (fn [conn-state]
                                      (-> conn-state
                                          (update :transaction assoc :failed true, :err error)
                                          (assoc :latest-submitted-tx-id tx-id))))
                  (cmd-send-error conn (err-protocol-violation (ex-message error))))
          :else (do
                  (swap! conn-state (fn [conn-state]
                                      (-> conn-state
                                          (dissoc :transaction)
                                          (assoc :latest-submitted-tx-id tx-id))))
                  (cmd-write-msg conn msg-command-complete {:command "COMMIT"})))))))

(defn cmd-rollback [{:keys [conn-state] :as conn}]
  (swap! conn-state dissoc :transaction)
  (cmd-write-msg conn msg-command-complete {:command "ROLLBACK"}))

;;; Sends description messages (e.g msg-row-description) to the client for a prepared statement or portal.
(defmethod handle-msg* :msg-describe [{:keys [conn-state] :as conn} {:keys [describe-type, describe-name]}]
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

(defmethod handle-msg* :msg-sync [{:keys [conn-state] :as conn} _]
  ;; Sync commands are sent by the client to commit transactions (we do not do anything here yet),
  ;; and to clear the error state of a :extended mode series of commands (e.g the parse/bind/execute dance)

  ;; TODO commit / rollback should be used here if not in an explicit tx?

  (when-not (:transaction @conn-state)
    ;;if outside an explicit transaction/transaction block (BEGIN/COMMIT) close any portals
    ;;as these are implicitly closed/cleaned up at the end of the transcation
    (doseq [portal-name (keys (:portals @conn-state))]
      (close-portal conn portal-name)))

  (cmd-send-ready conn)
  (swap! conn-state dissoc :skip-until-sync, :protocol))

(defmethod handle-msg* :msg-flush [conn _]
  (flush! (:frontend conn)))

(defn resolve-defaulted-params [declared-params inferred-params]
  (let [declared-params (vec declared-params)]
    (->> inferred-params
         (map-indexed (fn [idx inf-param]
                        (if-let [dec-param (nth declared-params idx nil)]
                          (if (= :default (:col-type dec-param))
                            inf-param
                            dec-param)
                          inf-param))))))

(defn parse
  "Responds to a msg-parse message that creates a prepared-statement."
  [{:keys [conn-state ^IXtdbInternal node] :as conn}
   {:keys [query param-oids]}]

  (let [{:keys [session latest-submitted-tx-id]} @conn-state
        {:keys [^Clock clock], {:strs [fallback_output_format] :as session-parameters} :parameters} session
        {:keys [statement-type] :as stmt} (-> (interpret-sql query {:default-tz (.getZone ^Clock (get-in @conn-state [:session :clock]))
                                                                    :latest-submitted-tx-id latest-submitted-tx-id
                                                                    :session-parameters session-parameters})
                                              (assoc :param-oids param-oids))]

    (when-let [err (permissibility-err conn stmt)]
      (throw (ex-info "parsing error"
                      {::client-error err})))

    (let [param-types (map types/pg-types-by-oid param-oids)]
      (if (= :query statement-type)
        (let [param-col-types (mapv :col-type param-types)]
          (when (some nil? param-col-types)
            (throw (ex-info "unsupported param-types in query"
                            {::client-error (err-protocol-violation (str "Unsupported param-types in query: "
                                                                         (pr-str (->> param-types
                                                                                      (into [] (comp (filter (comp nil? :col-type))
                                                                                                     (map :typname)
                                                                                                     (distinct)))))))})))

          (let [{:keys [ra-plan, ^Sql$DirectlyExecutableStatementContext parsed-query]} stmt
                query-opts {:after-tx-id (or latest-submitted-tx-id -1)
                            :tx-timeout (Duration/ofSeconds 1)
                            :param-types param-col-types
                            :default-tz (.getZone clock)}

                ^PreparedQuery pq (if ra-plan
                                    (.prepareRaQuery node ra-plan query-opts)
                                    (.prepareQuery node parsed-query query-opts))]
            (when-let [warnings (.warnings pq)]
              (doseq [warning warnings]
                (cmd-send-notice conn (notice-warning (plan/error-string warning)))))

            (assoc stmt
                   :prepared-query pq
                   :fields (mapv (partial types/field->pg-type fallback_output_format) (.columnFields pq))
                   :param-fields (->> (.paramFields pq)
                                      (map types/field->pg-type)
                                      (map #(set/rename-keys % {:column-oid :oid}))
                                      (resolve-defaulted-params param-types)))))

        ;; NOTE this means that for DML statments we assume the number and type of params is exactly
        ;; those specified by the client in arg-types, irrelevant of the number featured in the query string.
        ;; If a client subsequently binds a different number of params we will send an error msg
        (assoc stmt :param-fields param-types)))))

(defmethod handle-msg* :msg-parse [{:keys [conn-state] :as conn} {:keys [param-oids stmt-name] :as msg-data}]
  (swap! conn-state assoc :protocol :extended)

  (when-let [unsupported-param-oids (not-empty (into #{} (remove supported-param-oids) param-oids))]
    (throw (client-err (format "parameter type oids (%s) currently unsupported by xt" unsupported-param-oids))))

  (let [{:keys [statement-type] :as prepared-stmt} (parse conn msg-data)]
    (swap! conn-state (fn [{:keys [transaction] :as conn-state}]
                        (let [access-mode (when transaction
                                            (case statement-type
                                              :query :read-only
                                              :dml :read-write
                                              nil))]
                          (-> conn-state
                              (assoc-in [:prepared-statements stmt-name] prepared-stmt)
                              (cond-> access-mode (assoc-in [:transaction :access-mode] access-mode))))))

    (cmd-write-msg conn msg-parse-complete)))

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

(defn bind-stmt [{:keys [conn-state] :as conn} {:keys [params result-format] :as bind-msg} {:keys [statement-type] :as stmt}]
  (let [;; add data from bind-msg to stmt, for queries params are bound now, for dml this happens later during execute-tx.
        {:keys [prepared-query] :as stmt-with-bind-msg} (merge stmt bind-msg)]

    ;;if statement is a query, bind it, else use the statement as a portal
    (if (= :query statement-type)
      (let [{:keys [session transaction]} @conn-state
            {:keys [^Clock clock], {:strs [fallback_output_format]} :parameters} session

            xt-params (xtify-params conn params stmt-with-bind-msg)

            query-opts {:snapshot-time (or (:snapshot-time stmt) (:snapshot-time transaction))
                        :current-time (or (:current-time stmt)
                                          (:current-time transaction)
                                          (.instant clock))
                        :default-tz (.getZone clock)
                        :args xt-params}
            ^BoundQuery bound-query (.bind ^PreparedQuery prepared-query query-opts)
            fields (or (-> (map (partial types/field->pg-type fallback_output_format) (.columnFields bound-query))
                           (with-result-formats result-format))
                       (throw (client-err "invalid result format")))]
        (-> stmt-with-bind-msg
            (assoc :bound-query bound-query
                   :fields fields)))

      stmt-with-bind-msg)))

(defmethod handle-msg* :msg-bind [{:keys [conn-state] :as conn} {:keys [portal-name stmt-name] :as bind-msg}]
  (let [stmt (or (get-in @conn-state [:prepared-statements stmt-name])
                 (throw (client-err "no prepared statement")))
        portal (bind-stmt conn bind-msg stmt)]
    (swap! conn-state assoc-in [:portals portal-name] portal)
    (swap! conn-state update-in [:prepared-statements stmt-name :portals] (fnil conj #{}) portal-name)
    (cmd-write-msg conn msg-bind-complete)))

(defn execute-portal [conn {:keys [statement-type canned-response parameter tz value session-characteristics tx-characteristics] :as portal}]
  ;; TODO implement limit for queries that return rows

  (case statement-type
    :empty-query (cmd-write-msg conn msg-empty-query)
    :canned-response (cmd-write-canned-response conn canned-response)
    :set-session-parameter (cmd-set-session-parameter conn parameter value)
    :set-session-characteristics (cmd-set-session-characteristics conn session-characteristics)
    :set-role nil
    :set-transaction (cmd-set-transaction conn tx-characteristics)
    :set-time-zone (cmd-set-time-zone conn tz)
    :ignore (cmd-write-msg conn msg-command-complete {:command "IGNORED"})
    :begin (cmd-begin conn tx-characteristics)
    :rollback (cmd-rollback conn)
    :commit (cmd-commit conn)
    :query (cmd-exec-query conn portal)
    :dml (cmd-exec-dml conn portal)

    (throw (UnsupportedOperationException. (pr-str {:portal portal})))))

(defmethod handle-msg* :msg-execute [{:keys [conn-state] :as conn} {:keys [portal-name _limit]}]
  ;; Handles a msg-execute to run a previously bound portal (via msg-bind).
  (let [portal (or (get-in @conn-state [:portals portal-name])
                   (throw (ex-info "no such portal"
                                   {::client-error (err-protocol-violation "no such portal")})))]
    (execute-portal conn portal)))

(defmethod handle-msg* :msg-simple-query [{:keys [conn-state] :as conn} {:keys [query]}]
  (swap! conn-state assoc :protocol :simple)

  (try
    (let [{:keys [param-fields statement-type] :as prepared-stmt} (parse conn {:query query})]
      (when (seq param-fields)
        (throw (client-err "Parameters not allowed in simple queries")))

      (let [portal (bind-stmt conn {} prepared-stmt)]
        (try
          (when (contains? #{:query :canned-response} statement-type)
            ;; Client only expects to see a RowDescription (result of cmd-descibe)
            ;; for certain statement types
            (cmd-describe-portal conn portal))

          (execute-portal conn portal)
          (finally
            (util/close (:bound-query portal))))))

    ;; here we catch explicitly because we need to send the error, then a ready message
    (catch InterruptedException e (throw e))
    (catch Throwable e (send-ex conn e)))

  (cmd-send-ready conn))

;; ignored by xt
(defmethod handle-msg* :msg-password [_ _])

(defmethod handle-msg* ::default [_conn _]
  (throw (client-err "unknown client message")))

(defn handle-msg [{:keys [cid] :as conn} {:keys [msg-name] :as msg}]
  (try
    (log/trace "Read client msg" {:cid cid, :msg msg})

    (if (and (skip-until-sync? conn) (not= :msg-sync msg-name))
      (log/trace "Skipping msg until next sync due to error in extended protocol" {:cid cid, :msg msg})

      (handle-msg* conn msg))

    (catch InterruptedException e (throw e))

    (catch Throwable e
      (send-ex conn e))))

(defn- conn-loop [{:keys [cid, server, conn-state],
                   {:keys [^Socket socket]} :frontend,
                   !conn-closing? :!closing?,
                   :as conn}]
  (let [{:keys [port], !server-closing? :!closing?} server]
    (loop []
      (cond
        @!conn-closing?
        (log/trace "Connection loop exiting (closing)" {:port port, :cid cid})

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

        ;; go idle until we receive another msg from the client
        :else (do
                (when-let [msg (read-typed-msg conn)]
                  (handle-msg conn msg))

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
                                                           :frontend (->socket-frontend conn-socket),
                                                           :node node
                                                           :!closing? (atom false)
                                                           :conn-state !conn-state})
                                         (cmd-startup)
                                         (cond-> (nil? node) (assoc :node (->tmp-node server @!conn-state))))
                                     (catch Throwable t
                                       (log/warn t "error on conn startup")
                                       (throw t)))))]

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

(defn- accept-loop [{:keys [^ServerSocket accept-socket, ^ExecutorService thread-pool] :as server}]
  (try
    (loop []
      (cond
        (Thread/interrupted) (throw (InterruptedException.))

        (.isClosed accept-socket)
        (log/trace "Accept socket closed, exiting accept loop")

        :else
        (do
          (try
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
                                                                    "search_path" "public"
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
