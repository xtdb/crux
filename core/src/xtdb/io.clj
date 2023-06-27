(ns ^:no-doc xtdb.io
  (:require [clojure.instant :as instant]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [juxt.clojars-mirrors.nippy.v3v1v1.taoensso.nippy :as nippy])
  (:import (clojure.lang Box MapEntry)
           [java.io DataInputStream DataOutputStream File IOException Reader]
           (java.lang AutoCloseable)
           (java.lang.management BufferPoolMXBean ManagementFactory)
           (java.lang.ref PhantomReference ReferenceQueue)
           (java.net ServerSocket)
           (java.nio.file Files FileVisitResult SimpleFileVisitor)
           (java.nio.file.attribute FileAttribute)
           (java.text SimpleDateFormat)
           (java.time Duration)
           (java.util Collections Comparator Date IdentityHashMap Iterator Map PriorityQueue Properties)
           [java.util.concurrent ThreadFactory ExecutorService LinkedBlockingQueue ThreadPoolExecutor TimeUnit RejectedExecutionHandler]
           (java.util.concurrent.locks StampedLock)
           (xtdb.api ICursor)))

(s/def ::port (s/int-in 1 65536))

;; TODO: Replace with java.lang.ref.Cleaner in Java 9.
;; We currently still support Java 8.
(def ^:private ^ReferenceQueue reference-queue (ReferenceQueue.))
(def ^:private ^Map ref->cleanup-action (Collections/synchronizedMap (IdentityHashMap.)))

(defn- cleanup-loop []
  (try
    (loop [ref (.remove reference-queue)]
      (try
        (when-let [cleanup-action (.remove ref->cleanup-action ref)]
          (cleanup-action))
        (catch Exception e
          (log/error e "Error while running cleaner:")))
      (recur (.remove reference-queue)))
    (catch InterruptedException _)))

(def ^:private ^Thread cleaner-thread
  (do (when (and (bound? #'cleaner-thread) cleaner-thread)
        (.interrupt ^Thread cleaner-thread))
      (doto (Thread. ^Runnable cleanup-loop "xtdb.io.cleaner-thread")
        (.setDaemon true))))

(defn register-cleaner [object action]
  (locking cleaner-thread
    (when-not (.isAlive cleaner-thread)
      (.start cleaner-thread)))
  (.put ref->cleanup-action (PhantomReference. object reference-queue) action))

(defn format-rfc3339-date [^Date d]
  (when d
    (.format ^SimpleDateFormat (.get ^ThreadLocal @#'instant/thread-local-utc-date-format) d)))

(defn parse-rfc3339-or-millis-date [d]
  (if (re-find #"^\d+$" d)
    (Date. (Long/parseLong d))
    (instant/read-instant-date d)))

(defn format-duration-millis [^Duration d]
  (.toMillis d))

(defn free-port ^long []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(def ^:private files-to-delete (atom []))

(def file-deletion-visitor
  (proxy [SimpleFileVisitor] []
    (visitFile [file _]
      (Files/delete file)
      FileVisitResult/CONTINUE)

    (postVisitDirectory [dir _]
      (Files/delete dir)
      FileVisitResult/CONTINUE)))

(defn delete-dir [dir]
  (let [dir (io/file dir)]
    (when (.exists dir)
      (Files/walkFileTree (.toPath dir) file-deletion-visitor))))

(defn delete-file [file]
  (let [file (io/file file)]
    (when (.exists file)
      (.delete file))))

(defn create-tmpdir ^java.io.File [dir-name]
  (let [f (.toFile (Files/createTempDirectory dir-name (make-array FileAttribute 0)))
        known-files (swap! files-to-delete conj f)]
    (when (= 1 (count known-files))
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable (fn []
                            (doseq [f @files-to-delete]
                              (delete-dir f)))
                "xtdb.io.shutdown-hook-thread")))

    f))

(defn folder-size
  "Total size of a file or folder in bytes"
  [^java.io.File f]
  (cond
    (nil? f) 0
    (string? f) (folder-size (io/file f))
    (.isDirectory f) (transduce (map folder-size) + (.listFiles f))
    :else (.length f)))

(defn try-close [c]
  (try
    (when (instance? AutoCloseable c)
      (.close ^AutoCloseable c))
    (catch Throwable t
      (log/error t "Could not close:" c))))

(defn load-properties [^Reader in]
  (->> (doto (Properties.)
         (.load in))
       (into {})))

(defn native-image? []
  (boolean (System/getProperty "org.graalvm.nativeimage.kind")))

;; External Merge Sort

(defn- new-merge-sort-priority-queue ^PriorityQueue [^Comparator comp sorted-seqs]
  (let [sorted-seqs (remove empty? sorted-seqs)
        pq-comp (reify Comparator
                  (compare [_ [a] [b]]
                    (.compare comp a b)))]
    (doto (PriorityQueue. (max 1 (count sorted-seqs)) pq-comp)
      (.addAll sorted-seqs))))

(defn- merge-sort-priority-queue->seq [^PriorityQueue pq]
  ((fn step []
     (lazy-seq
      (let [[x & xs] (.poll pq)]
        (when x
          (when xs
            (.add pq xs))
          (cons x (step))))))))

(defn merge-sort
  ([sorted-seqs]
   (merge-sort compare sorted-seqs))
  ([comp sorted-seqs]
   (->> (new-merge-sort-priority-queue comp sorted-seqs)
        (merge-sort-priority-queue->seq))))

(def default-external-sort-part-size (* 1024 1024))

(defn with-nippy-thaw-all* [f]
  (binding [nippy/*thaw-serializable-allowlist* #{"*"}]
    (f)))

(defmacro with-nippy-thaw-all [& body]
  `(with-nippy-thaw-all* (fn [] ~@body)))

(defn external-sort
  ([seq]
   (external-sort compare seq))
  ([comp seq]
   (external-sort comp seq default-external-sort-part-size))
  ([comp seq external-sort-part-size]
   (with-nippy-thaw-all
     (let [parts (partition-all external-sort-part-size seq)]
       (if (nil? (second parts))
         (sort comp (first parts))
         (let [files (->> parts
                          (reduce
                           (fn [acc part]
                             (let [file (doto (File/createTempFile "xtdb-external-sort" ".nippy")
                                          (.deleteOnExit))]
                               (with-open [out (DataOutputStream. (io/output-stream file))]
                                 (doseq [x (sort comp part)]
                                   (nippy/freeze-to-out! out x)))
                               (conj acc file)))
                           []))
               seq+cleaner-actions (for [^File file files]
                                     (let [in (DataInputStream. (io/input-stream file))
                                           cleaner-action (fn []
                                                            (.close in)
                                                            (.delete file))
                                           seq ((fn step []
                                                  (lazy-seq
                                                   (try
                                                     (when-let [x (nippy/thaw-from-in! in)]
                                                       (cons x (step)))
                                                     (catch Exception e
                                                       (cleaner-action)
                                                       (if (or (instance? IOException e)
                                                               (instance? IOException (.getCause e)))
                                                         nil
                                                         (throw e)))))))]
                                       [seq cleaner-action]))
               pq (->> (map first seq+cleaner-actions)
                       (new-merge-sort-priority-queue comp))]
           (doseq [[_ cleaner-action] seq+cleaner-actions]
             (register-cleaner pq cleaner-action))
           (merge-sort-priority-queue->seq pq)))))))

(defmacro with-read-lock [lock & body]
  `(let [^StampedLock lock# ~lock
         stamp# (.readLock lock#)]
     (try
       ~@body
       (finally
         (.unlock lock# stamp#)))))

(defmacro with-write-lock [lock & body]
  `(let [^StampedLock lock# ~lock
         stamp# (.writeLock lock#)]
     (try
       ~@body
       (finally
         (.unlock lock# stamp#)))))

(defn pr-edn-str ^String [xs]
  (binding [*print-length* nil
            *print-level* nil
            *print-namespace-maps* false]
    (pr-str xs)))

(defn pid ^long []
  (let [[pid] (str/split (.getName (ManagementFactory/getRuntimeMXBean)) #"@")]
    (Long/parseLong pid)))

(defn statm []
  (let [os (System/getProperty "os.name")]
    (if (re-find #"(?i)linux" os)
      (let [page-size (Long/parseLong (str/trim (:out (sh/sh "getconf" "PAGESIZE"))))]
        (zipmap
         [:size :resident :share :text :lib :data :dt]
         (for [stat (str/split (:out (sh/sh "cat" (str "/proc/" (pid) "/statm"))) #"\s+")]
           (* page-size (Long/parseLong stat)))))
      (throw (UnsupportedOperationException. (str "cannot get memory statistics on: " os))))))

(defn buffer-pool-usage []
  (vec (for [^BufferPoolMXBean b (ManagementFactory/getPlatformMXBeans BufferPoolMXBean)]
         {:name (.getName b)
          :count (.getCount b)
          :memory-used (.getMemoryUsed b)
          :total-capacity (.getTotalCapacity b)})))

(defn memory-usage []
  [(assoc (dissoc (bean (.getNonHeapMemoryUsage (ManagementFactory/getMemoryMXBean))) :class) :name "non-heap")
   (assoc (dissoc (bean (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))) :class) :name "heap")])

(def uncaught-exception-handler
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread throwable]
      (log/error throwable "Uncaught exception:"))))

(defn install-uncaught-exception-handler! []
  (when-not (Thread/getDefaultUncaughtExceptionHandler)
    (Thread/setDefaultUncaughtExceptionHandler uncaught-exception-handler)))

(defn thread-factory ^java.util.concurrent.ThreadFactory [name-prefix]
  (let [idx (atom 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. r)
          (.setName (str name-prefix "-" (swap! idx inc)))
          (.setUncaughtExceptionHandler uncaught-exception-handler))))))

(defn bounded-thread-pool ^ExecutorService [^long pool-size, ^long queue-size, thread-factory]
  (let [queue (LinkedBlockingQueue. queue-size)]
    (ThreadPoolExecutor. 1 pool-size
                         0 TimeUnit/MILLISECONDS
                         queue
                         thread-factory
                         (reify RejectedExecutionHandler
                           (rejectedExecution [_ runnable executor]
                             (.put queue runnable))))))

(defrecord Cursor [close-fn ^Iterator lazy-seq-iterator]
  ICursor
  (next [this]
    (.next lazy-seq-iterator))

  (hasNext [this]
    (.hasNext lazy-seq-iterator))

  (close [_]
    (close-fn)))

(defn ->cursor [close-fn ^Iterable sq]
  (->Cursor close-fn (.iterator (lazy-seq sq))))

(def empty-cursor
  (->cursor #() []))

(defn update-if [m k f & args]
  (if (contains? m k)
    (apply update m k f args)
    m))

(defn jnr-available? []
  (try
    (import 'jnr.ffi.Pointer)
    true
    (catch ClassNotFoundException e
      false)))

(definterface GLibC
  (^int mallopt [^int param ^int value])
  (^String gnu_get_libc_version []))

(def ^:private ^:const M_ARENA_MAX -8)
(def malloc-arena-max (atom (System/getenv "MALLOC_ARENA_MAX")))

(def ^:private glibc
  (memoize (fn ^xtdb.io.GLibC []
             (when (jnr-available?)
               (try
                 (eval `(.load (jnr.ffi.LibraryLoader/create GLibC) "c"))
                 (catch UnsatisfiedLinkError e
                   (log/debug "Could not load glibc")))))))

(defn glibc? []
  (if-let [glibc ^GLibC (glibc)]
    (try
      (.gnu_get_libc_version glibc)
      true
      (catch UnsatisfiedLinkError e
        (log/debug "Could not call glibc gnu_get_libc_version")
        false))
    false))

(defn try-set-malloc-arena-max [^long m-arena-max]
  (when (and (nil? @malloc-arena-max)
             (jnr-available?)
             (glibc?))
    (try
      (let [glibc ^GLibC (glibc)
            result (.mallopt glibc M_ARENA_MAX m-arena-max)]
        (when (not= 1 result)
          (log/warn "Error when calling mallopt:" result "glibc version:" (.gnu_get_libc_version glibc)))
        (reset! malloc-arena-max m-arena-max)
        result)
      (catch UnsatisfiedLinkError e
        (log/debug "Could not call glibc mallopt")))))

(defn map-vals
  ([f]
   (fn [rf]
     (fn
       ([] (rf))

       ([acc el]
        (rf acc (MapEntry/create (key el) (f (val el)))))

       ([acc] (rf acc)))))

  ([f m]
   (->> m
        (into {} (map (fn [v]
                        (MapEntry/create (key v) (f (val v)))))))))

(defn conform-tx-log-entry [tx entry]
  (into tx
        (cond
          (vector? entry) {:xtdb.tx.event/tx-events entry}
          (map? entry) entry
          :else (throw (IllegalStateException. (format "unexpected value on tx-log: '%s'" (pr-str entry)))))))

(defn sleep
  "Wraps Thread/sleep so that it can be overridden in tests."
  [ms]
  (Thread/sleep (long ms)))

(defn exp-backoff
  "Calls f, while it throws a retryable Throwable, invokes it again after a back-off period.
  The back-off period grows exponentially until some max.

  Back off can be interrupted for cancellation.

  Options:

  :retryable
  A function that returns true if the given Throwable is retryable. If not retryable the exception will be rethrown.
  Default uses the ex-data :cognitect.anomalies/category to determine whether to retry. Will retry on unavailable, or busy.
  If anomaly is nil or not-found, any java.lang.Exception is considered retryable.

  :start-wait-ms (default 50)
  The amount of time to wait before the first retry.

  :max-wait-ms (default 60000, 1 minute)
  The maximum amount of time to wait, to stop the exponential growth of the backoff.

  :wait-mul (default 2)
  The multiplier to be applied to the wait-ms every retry.

  :on-retry (default logs the exception as an ERROR)
  Function that is applied to the exception each retry, perhaps for logging or monitoring."
  ([f] (exp-backoff f {}))
  ([f {:keys [retryable
              on-retry
              wait-mul
              start-wait-ms
              max-wait-ms]
       :or {retryable (fn default-retryable [t]
                        (let [data (ex-data t)
                              not-found (Object.)
                              category (:cognitect.anomalies/category data not-found)]
                          (if (identical? not-found category)
                            (instance? Exception t)
                            (contains? #{:cognitect.anomalies/unavailable, :cognitect.anomalies/busy} category))))
            start-wait-ms 50
            on-retry #(log/error % "Retryable exception caught during exp-backoff loop")
            wait-mul 2
            max-wait-ms 60000}}]
   (letfn [(try-call [wait-ms]
             (try
               (Box. (f))
               (catch InterruptedException e (throw e))
               (catch Throwable t
                 (when-not (retryable t) (throw t))
                 (on-retry t)
                 (sleep wait-ms)
                 (when (Thread/interrupted) (throw (InterruptedException.))) ; sleep should have thrown, but we might redefine it in tests
                 (partial try-call (min max-wait-ms (* wait-mul wait-ms))))))]
     (.-val ^Box (trampoline try-call (min max-wait-ms start-wait-ms))))))
