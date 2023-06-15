(ns ^:no-doc xtdb.remote-api-client
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.clj-http.v3v12v2.clj-http.client :as http]
            [xtdb.api :as xt]
            [xtdb.codec :as c]
            [xtdb.error :as err]
            [xtdb.io :as xio]
            [xtdb.query-state :as qs])
  (:import (com.nimbusds.jwt SignedJWT)
           (java.io Closeable InputStreamReader PushbackReader)
           (java.time Instant)
           (java.util Date)
           (java.util.function Supplier)
           (xtdb.api RemoteClientOptions)))

(defn- edn-list->lazy-seq [in]
  (let [in (PushbackReader. (InputStreamReader. in))]
    (condp = (.read in)
      -1 nil
      (int \() (->> (repeatedly #(try
                                   (edn/read {:readers {'xtdb/id c/id-edn-reader
                                                        'xtdb/query-state qs/->QueryState
                                                        'xtdb/query-error qs/->QueryError}
                                              :eof ::eof} in)
                                   (catch RuntimeException e
                                     (if (= "Unmatched delimiter: )" (.getMessage e))
                                       ::eof
                                       (throw e)))))
                    (take-while #(not= ::eof %)))

      (throw (RuntimeException. "Expected delimiter: (")))))

(def ^{:doc "Can be rebound using binding or alter-var-root to a
  function that takes a request map and returns a response
  map. The :body for POSTs will be provided as an EDN string by the
  caller. Should return the result body as a string by default, or as
  a stream when the :as :stream option is set.

  Will be called with :url, :method, :body, :headers and
  optionally :as with the value :stream.

  Expects :body, :status and :headers in the response map. Should not
  throw exceptions based on status codes of completed requests."
       :dynamic true}
  *internal-http-request-fn*)

(defn- init-internal-http-request-fn []
  (when (not (bound? #'*internal-http-request-fn*))
    (alter-var-root
     #'*internal-http-request-fn*
     (constantly (fn [opts]
                   (http/request (into {:as "UTF-8" :throw-exceptions false} opts)))))))

(defn- api-request-sync
  ([url {:keys [body http-opts ->jwt-token]}]
   (let [{:keys [body status] :as result}
         (*internal-http-request-fn* (merge {:url url
                                             :method :post
                                             :headers (merge (when body
                                                               {"Content-Type" "application/edn"})
                                                             (when ->jwt-token
                                                               {"Authorization" (str "Bearer " (->jwt-token))}))
                                             :body (some-> body xio/pr-edn-str)
                                             :accept :edn
                                             :as "UTF-8"
                                             :throw-exceptions false}
                                            (update http-opts :query-params #(into {} (remove (comp nil? val) %)))))]
     (cond
       (= 404 status)
       nil

       (= 400 status)
       (let [error-data (edn/read-string (cond-> body
                                           (= :stream (:as http-opts)) slurp))]
         (throw (case (::err/error-type error-data)
                  :illegal-argument (err/illegal-arg (::err/error-key error-data) error-data)
                  :node-out-of-sync (err/node-out-of-sync error-data)
                  (ex-info "Generic remote client error" error-data))))

       (and (<= 200 status) (< status 400))
       (if (string? body)
         (c/read-edn-string-with-readers body)
         body)

       :else
       (throw (ex-info (str "HTTP status " status) result))))))

(defrecord RemoteDatasource [url valid-time tx-time tx-id ->jwt-token basis-qps]
  Closeable
  (close [_])

  xt/PXtdbDatasource
  (entity [_ eid]
    (api-request-sync (str url "/_xtdb/entity")
                      {:->jwt-token ->jwt-token
                       :http-opts {:method :get
                                   :query-params (-> basis-qps
                                                     (assoc :eid-edn (pr-str eid)))}}))
  (entity-tx [_ eid]
    (api-request-sync (str url "/_xtdb/entity-tx")
                      {:http-opts {:method :get
                                   :query-params (-> basis-qps
                                                     (assoc :eid-edn (pr-str eid)))}
                       :->jwt-token ->jwt-token}))

  (q* [this query in-args]
    (with-open [res (xt/open-q* this query in-args)]
      (if (:order-by query)
        (vec (iterator-seq res))
        (set (iterator-seq res)))))

  (open-q* [_ query in-args]
    (let [in (api-request-sync (str url "/_xtdb/query")
                               {:->jwt-token ->jwt-token
                                :http-opts {:as :stream
                                            :method :post
                                            :query-params basis-qps}
                                :body {:query (pr-str query)
                                       :in-args (vec in-args)}})]
      (xio/->cursor #(.close ^Closeable in) (edn-list->lazy-seq in))))

  (pull [this projection eid]
    (let [?eid (gensym '?eid)
          projection (cond-> projection (string? projection) c/read-edn-string-with-readers)]
      (->> (xt/q this
                  {:find [(list 'pull ?eid projection)]
                   :in [?eid]}
                  eid)
           ffirst)))

  (pull-many [this projection eids]
    (let [?eid (gensym '?eid)
          projection (cond-> projection (string? projection) c/read-edn-string-with-readers)]
      (->> (xt/q this
                  {:find [(list 'pull ?eid projection)]
                   :in [[?eid '...]]}
                  eids)
           (mapv first))))

  (entity-history [this eid sort-order] (xt/entity-history this eid sort-order {}))

  (entity-history [this eid sort-order opts]
   (with-open [history (xt/open-entity-history this eid sort-order opts)]
     (vec (iterator-seq history))))

  (open-entity-history [this eid sort-order] (xt/open-entity-history this eid sort-order {}))

  (open-entity-history [_ eid sort-order opts]
   (let [opts (assoc opts :sort-order sort-order)
         qps (-> basis-qps
                 (assoc :eid-edn (pr-str eid)
                        :history true

                        :sort-order (name sort-order)
                        :with-corrections (:with-corrections? opts)
                        :with-docs (:with-docs? opts)

                        :start-valid-time (some-> (:start-valid-time opts) (xio/format-rfc3339-date))
                        :start-tx-time (some-> (get-in opts [:start-tx ::xt/tx-time])
                                               (xio/format-rfc3339-date))
                        :start-tx-id (get-in opts [:start-tx ::xt/tx-id])

                        :end-valid-time (some-> (:end-valid-time opts) (xio/format-rfc3339-date))
                        :end-tx-time (some-> (get-in opts [:end-tx ::xt/tx-time])
                                             (xio/format-rfc3339-date))
                        :end-tx-id (get-in opts [:end-tx ::xt/tx-id])))]

     (if-let [in (api-request-sync (str url "/_xtdb/entity")
                                   {:http-opts {:as :stream
                                                :method :get
                                                :query-params qps}
                                    :->jwt-token ->jwt-token})]
       (xio/->cursor #(.close ^java.io.Closeable in) (edn-list->lazy-seq in))
       xio/empty-cursor)))

  (valid-time [_] valid-time)

  (transaction-time [_] tx-time)

  (db-basis [_]
    {::xt/valid-time valid-time
     ::xt/tx {::xt/tx-time tx-time
              ::xt/tx-id tx-id}}))

(defn- merge-await-opts [{{old-tx-id ::xt/tx-id, old-tx-time ::xt/tx-time} :tx}
                         {:keys [timeout], {new-tx-id ::xt/tx-id, new-tx-time ::xt/tx-time} :tx}]
  {:tx {::xt/tx-id (->> [old-tx-id new-tx-id] (remove nil?) sort last)
        ::xt/tx-time (->> [old-tx-time new-tx-time] (remove nil?) sort last)}
   :timeout timeout})

(defn- ->basis-qps [{:keys [valid-time tx],
                     {await-tx :tx, await-tx-timeout :timeout} :await-opts}]
  {:valid-time (some-> valid-time (xio/format-rfc3339-date))
   :tx-time (some-> (::xt/tx-time tx) (xio/format-rfc3339-date))
   :tx-id (::xt/tx-id tx)
   :await-tx-id (::xt/tx-id await-tx)
   :await-tx-time (some-> (::xt/tx-time await-tx) (xio/format-rfc3339-date))
   :await-tx-timeout (some-> await-tx-timeout (xio/format-duration-millis))})

(defrecord RemoteApiClient [url ->jwt-token !await-opts]
  Closeable
  (close [_])

  xt/DBProvider
  (db [this] (xt/db this {}))

  (db [this valid-time tx-time]
   (xt/db this {::xt/valid-time valid-time
                ::xt/tx-time tx-time}))

  (db [_ valid-time-or-basis]
   (if (instance? Date valid-time-or-basis)
     (recur {::xt/valid-time valid-time-or-basis})
     (let [db-basis valid-time-or-basis
           basis-qps (->basis-qps {:valid-time (::xt/valid-time db-basis)
                                   :tx {::xt/tx-time (or (get-in db-basis [::xt/tx ::xt/tx-time])
                                                         (::xt/tx-time db-basis))
                                        ::xt/tx-id (or (get-in db-basis [::xt/tx ::xt/tx-id])
                                                       (::xt/tx-id db-basis))}
                                   :await-opts @!await-opts})

           resolved-tx (api-request-sync (str url "/_xtdb/db")
                                         {:http-opts {:method :get
                                                      :query-params basis-qps}
                                          :->jwt-token ->jwt-token})]
       (->RemoteDatasource url
                           (::xt/valid-time resolved-tx)
                           (get-in resolved-tx [::xt/tx ::xt/tx-time])
                           (get-in resolved-tx [::xt/tx ::xt/tx-id])
                           ->jwt-token
                           (->basis-qps {:valid-time (::xt/valid-time db-basis)
                                         :tx (::xt/tx resolved-tx)
                                         :await-opts @!await-opts})))))

  (open-db [this] (xt/db this))

  (open-db [this valid-time-or-basis] (xt/db this valid-time-or-basis))

  (open-db [this valid-time tx-time] (xt/db this valid-time tx-time))

  xt/PXtdb
  (status [_]
    (api-request-sync (str url "/_xtdb/status")
                      {:http-opts {:method :get}
                       :->jwt-token ->jwt-token}))

  (tx-committed? [_ submitted-tx]
    (-> (api-request-sync (str url "/_xtdb/tx-committed")
                          {:http-opts {:method :get
                                       :query-params (merge (->basis-qps {:await-opts @!await-opts})
                                                            {:tx-id (::xt/tx-id submitted-tx)})}
                           :->jwt-token ->jwt-token})
        (get :tx-committed?)))

  (sync [this] (xt/sync this nil))

  (sync [this timeout]
    (when-let [tx (xt/latest-submitted-tx this)]
      (xt/await-tx this tx timeout)))

  (sync [this tx-time timeout]
   #_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
   (defonce warn-on-deprecated-sync
     (log/warn "(sync tx-time node <timeout?>) is deprecated, replace with either (await-tx-time node tx-time <timeout?>) or, preferably, (await-tx node tx <timeout?>)"))

   (xt/await-tx-time this tx-time timeout))

  (await-tx [this submitted-tx] (xt/await-tx this submitted-tx nil))
  (await-tx [_ submitted-tx timeout]
    ;; continue to call the await-tx endpoint so that new clients still work with old servers
    (api-request-sync (str url "/_xtdb/await-tx")
                      {:http-opts {:method :get
                                   :query-params {:tx-id (::xt/tx-id submitted-tx)
                                                 :timeout (some-> timeout (xio/format-duration-millis))}}
                       :->jwt-token ->jwt-token})

    (swap! !await-opts merge-await-opts {:tx submitted-tx, :timeout timeout})
    submitted-tx)

  (await-tx-time [this tx-time] (xt/await-tx-time this tx-time nil))
  (await-tx-time [_ tx-time timeout]
    ;; continue to call the await-tx-time endpoint so that new clients still work with old servers
    (api-request-sync (str url "/_xtdb/await-tx-time")
                      {:http-opts {:method :get
                                   :query-params {:tx-time (xio/format-rfc3339-date tx-time)
                                                  :timeout (some-> timeout (xio/format-duration-millis))}}
                       :->jwt-token ->jwt-token})

    (swap! !await-opts merge-await-opts {:tx {::xt/tx-time tx-time}, :timeout timeout})
    tx-time)

  (listen [_ _event-opts _f]
    (throw (UnsupportedOperationException. "'listen' not supported on remote clients")))

  (latest-completed-tx [_]
    (api-request-sync (str url "/_xtdb/latest-completed-tx")
                      {:http-opts {:method :get}
                       :->jwt-token ->jwt-token}))

  (latest-submitted-tx [_]
    (api-request-sync (str url "/_xtdb/latest-submitted-tx")
                      {:http-opts {:method :get}
                       :->jwt-token ->jwt-token}))

  (attribute-stats [_]
    (api-request-sync (str url "/_xtdb/attribute-stats")
                      {:http-opts {:method :get}
                       :->jwt-token ->jwt-token}))

  (active-queries [_]
    (->> (api-request-sync (str url "/_xtdb/active-queries")
                           {:http-opts {:method :get}
                            :->jwt-token ->jwt-token})
         (map qs/->QueryState)))

  (recent-queries [_]
    (->> (api-request-sync (str url "/_xtdb/recent-queries")
                           {:http-opts {:method :get}
                            :->jwt-token ->jwt-token})
         (map qs/->QueryState)))

  (slowest-queries [_]
    (->> (api-request-sync (str url "/_xtdb/slowest-queries")
                           {:http-opts {:method :get}
                            :->jwt-token ->jwt-token})
         (map qs/->QueryState)))

  xt/PXtdbSubmitClient
  (submit-tx [this tx-ops] (xt/submit-tx this tx-ops {}))

  (submit-tx [_ tx-ops opts]
    (let [tx-ops (xt/conform-tx-ops tx-ops)]
      (try
        (api-request-sync (str url "/_xtdb/submit-tx")
                          {:body {:tx-ops tx-ops
                                  ::xt/submit-tx-opts opts}
                           :->jwt-token ->jwt-token})
        (catch Exception e
          (let [data (ex-data e)]
            (when (and (= 403 (:status data))
                       (string/includes? (:body data) "read-only HTTP node"))
              (throw (UnsupportedOperationException. "read-only HTTP node")))
            (throw e))))))

  (open-tx-log ^xtdb.api.ICursor [_ after-tx-id with-ops?]
    (let [with-ops? (boolean with-ops?)
          in (api-request-sync (str url "/_xtdb/tx-log")
                               {:http-opts {:method :get
                                            :as :stream
                                            :query-params (merge {:after-tx-id after-tx-id
                                                                  :with-ops? with-ops?}
                                                                 (->basis-qps {:await-opts @!await-opts}))}
                                :->jwt-token ->jwt-token})]

      (xio/->cursor #(.close ^Closeable in)
                    (edn-list->lazy-seq in)))))

(defn- ^:dynamic *now* ^Instant []
  (Instant/now))

(defn- ->jwt-token-fn [^Supplier jwt-supplier]
  (let [!token-cache (atom nil)]
    (fn []
      (or (when-let [token @!token-cache]
            (let [expiration-time (.minusSeconds ^Instant (:token-expiration token) 5)
                  current-time (*now*)]
              (when (.isBefore current-time expiration-time)
                (:token token))))
          (let [^String new-token (.get jwt-supplier)
                ^Instant new-token-exp (-> (SignedJWT/parse new-token)
                                           (.getJWTClaimsSet)
                                           (.getExpirationTime)
                                           (.toInstant))]
            (reset! !token-cache {:token new-token :token-expiration new-token-exp})
            new-token)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn new-api-client
  ([url] (new-api-client url nil))

  ([url ^RemoteClientOptions options]
   (init-internal-http-request-fn)
   (->RemoteApiClient url (some-> options (.-jwtSupplier) ->jwt-token-fn) (atom nil))))
