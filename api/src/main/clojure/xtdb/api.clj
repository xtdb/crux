(ns xtdb.api
  "This namespace is the main public Clojure API to XTDB.

  It lives in the `com.xtdb/xtdb-api` artifact - include this in your dependency manager of choice.

  To start a node, you will additionally need:

  * `xtdb.node`, for an in-process node.
  * `xtdb.client`, for a remote client."

  (:require [xtdb.backtick :as backtick]
            [xtdb.error :as err]
            [xtdb.protocols :as xtp]
            [xtdb.serde :as serde])
  (:import (clojure.lang IReduceInit)
           (java.io Writer)
           (java.util Iterator)
           [java.util.stream Stream]
           (xtdb.api TransactionKey)
           xtdb.types.ClojureForm))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->ClojureForm [form]
  (ClojureForm. form))

(defmethod print-dup ClojureForm [^ClojureForm clj-form ^Writer w]
  (.write w "#xt/clj-form ")
  (print-method (.form clj-form) w))

(defmethod print-method ClojureForm [clj-form w]
  (print-dup clj-form w))

(defn plan-q
  "General query execution function for controlling the realized result set.

  Returns a reducible that, when reduced (with an initial value), runs the query and yields the result.
  `plan-q` returns an IReduceInit object so you must provide an initial value when calling reduce on it.

  The main use case for `plan-q` is to stream large results sets without having the entire result set in memory.
  A common way to do this is to call run! together with a side-effecting function process-row!
  (which could for example write the row to a file):

  (run! process-row! (xt/plan-q node ...))

  The arguments are the same as for `q`."

  (^clojure.lang.IReduceInit [node query] (plan-q node query {}))
  (^clojure.lang.IReduceInit [node query opts]
   (let [query-opts (-> opts
                        (update :key-fn (comp serde/read-key-fn (fnil identity :kebab-case-keyword)))
                        (update :after-tx-id (fnil identity (xtp/latest-submitted-tx-id node))))]
     (reify IReduceInit
       (reduce [_ f start]
         (with-open [^Stream res (cond
                                   (string? query) (xtp/open-sql-query node query query-opts)
                                   (vector? query) (let [[sql & args] query]
                                                     (xtp/open-sql-query node sql (assoc query-opts :args args)))
                                   (seq? query) (xtp/open-xtql-query node query query-opts)
                                   :else (throw (err/illegal-arg :unknown-query-type {:query query, :type (type query)})))]
           (let [^Iterator itr (.iterator res)]
             (loop [acc start]
               (if-not (.hasNext itr)
                 acc
                 (let [acc (f acc (.next itr))]
                   (if (reduced? acc)
                     (deref acc)
                     (recur acc))))))))))))

(defn q
  "query an XTDB node.

  - query: either an XTQL or SQL query.
  - opts:
    - `:snapshot-time`: see 'Transaction Basis'
    - `:current-time`: override wall-clock time to use in functions that require it
    - `:args`: arguments to pass to the query.
    - `:default-tz`: overrides the default time zone for the query

  For example:

  (q node '(from ...))

  (q node '(from :foo [{:a $a, :b $b}])
      {:a a-value, :b b-value})

  (q node \"SELECT foo.id, foo.v FROM foo WHERE foo.id = 'my-foo'\")
  (q node [\"SELECT foo.id, foo.v FROM foo WHERE foo.id = ?\" foo-id])

  Please see XTQL/SQL query language docs for more details.

  This function returns the results of its query as a vector of maps

  Transaction Basis:

  In XTDB there are a number of ways to control at what point in time a query is run -
  this is done via a snapshot-time basis optionally supplied as part of the query map.

  In the case a basis is not provided the query is guaranteed to run sometime after
  the latest transaction submitted by this connection/node.

  Alternatively a specific snapshot-time can be supplied,
  in this case the query will be run exactly at that system-time, ensuring the repeatability of queries.

  (q node '(from ...)
     {:snapshot-time #inst \"2020-01-02\"})"
  ([node query] (q node query {}))

  ([node query opts]
   (into [] (plan-q node query opts))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn submit-tx
  "Writes transactions to the log for processing

  tx-ops: XTQL/SQL style transactions.
    [[:put-docs :table {:xt/id \"my-id\", ...}]
     [:delete-docs :table \"my-id\"]

     [\"INSERT INTO foo (_id, a, b) VALUES ('foo', ?, ?)\" 0 1]

     ;; batches
     [:sql \"INSERT INTO foo (_id, a, b) VALUES ('foo', ?, ?)\"
      [2 3] [4 5] [6 7]]

     \"UPDATE foo SET b = 1\"]

  Returns a map with details about the submitted transaction, including system-time and tx-id.

  opts (map):
   - :system-time
     overrides system-time for the transaction,
     mustn't be earlier than any previous system-time

   - :default-tz
     overrides the default time zone for the transaction,
     should be an instance of java.time.ZoneId"

  (^TransactionKey [node, tx-ops] (submit-tx node tx-ops {}))
  (^TransactionKey [node, tx-ops tx-opts]
   (xtp/submit-tx node (vec tx-ops) tx-opts)))

(defn execute-tx
  "Executes a transaction; blocks waiting for the receiving node to index it.

  tx-ops: XTQL/SQL style transactions.
    [[:put-docs :table {:xt/id \"my-id\", ...}]
     [:delete-docs :table \"my-id\"]

     [:sql \"INSERT INTO foo (_id, a, b) VALUES ('foo', ?, ?)\"
      [0 1]]

     [:sql \"INSERT INTO foo (_id, a, b) VALUES ('foo', ?, ?)\"
      [2 3] [4 5] [6 7]]

     [:sql \"UPDATE foo SET b = 1\"]]

  Returns a map with details about the submitted transaction, including system-time and tx-id.

  opts (map):
   - :system-time
     overrides system-time for the transaction,
     mustn't be earlier than any previous system-time

   - :default-tz
     overrides the default time zone for the transaction,
     should be an instance of java.time.ZoneId"

  (^TransactionKey [node, tx-ops] (execute-tx node tx-ops {}))
  (^TransactionKey [node, tx-ops tx-opts]
   (xtp/execute-tx node (vec tx-ops) tx-opts)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn status
  "Returns the status of this node as a map,
  including details of both the latest submitted and completed tx"
  [node]
  (xtp/status node))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro template
  "This macro quotes the given query, but additionally allows you to use Clojure's unquote (`~`) and unquote-splicing (`~@`) forms within the quoted form.

  Usage:

  (defn build-posts-query [{:keys [with-author?]}]
    (xt/template (from :posts [{:xt/id id} text
                               ~@(when with-author?
                                   '[author])])))"

  {:clj-kondo/ignore [:unresolved-symbol :unresolved-namespace]}
  [query]

  (backtick/quote-fn query))
