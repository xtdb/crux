(ns xtdb.bench2.auctionmark
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [xtdb.bench2 :as b2]
            [xtdb.bench2.xtdb2 :as bxt2]
            [xtdb.test-util :as tu])
  (:import (java.time Duration Instant)
           (java.util ArrayList Random)
           (java.util.concurrent ConcurrentHashMap)))

(defn random-price [worker] (.nextDouble (b2/rng worker)))

(def user-id (partial str "u_"))
(def region-id (partial str "r_"))
(def item-id (partial str "i_"))
(def item-bid-id (partial str "ib_"))
(def category-id (partial str "c_"))
(def global-attribute-group-id (partial str "gag_"))
(def gag-id global-attribute-group-id)
(def global-attribute-value-id (partial str "gav_"))
(def gav-id global-attribute-value-id)

(def user-attribute-id (partial str "ua_"))

(defn generate-user [worker]
  (let [u_id (b2/increment worker user-id)]
    {:xt/id u_id
     :u_id u_id
     :u_r_id (b2/sample-flat worker region-id)
     :u_rating 0
     :u_balance 0.0
     :u_created (b2/current-timestamp worker)
     :u_sattr0 (b2/random-str worker)
     :u_sattr1 (b2/random-str worker)
     :u_sattr2 (b2/random-str worker)
     :u_sattr3 (b2/random-str worker)
     :u_sattr4 (b2/random-str worker)
     :u_sattr5 (b2/random-str worker)
     :u_sattr6 (b2/random-str worker)
     :u_sattr7 (b2/random-str worker)}))

(defn proc-new-user
  "Creates a new USER record. The rating and balance are both set to zero.

  The benchmark randomly selects id from a pool of region ids as an input for u_r_id parameter using flat distribution."
  [worker]
  (xt/submit-tx (:sut worker) [(xt/put :user (generate-user worker))]))

(def item-query
  '(from :item [{:xt/id $i}
                xt/id i_id i_u_id i_c_id i_name i_description i_user_attributes i_initial_price
                i_current_price i_num_bids i_num_images i_num_global_attrs i_start_date
                i_end_date i_status]))

(def item-max-bid-query
  '(from :item-max-bid [{:xt/id $imb}
                        [xt/id imb_i_id imb_u_id imb_ib_id imb_ib_i_id imb_ib_u_id imb_created]]))

(def tx-fn-new-bid
  "Transaction function.

  Enters a new bid for an item"
  (xt/template
   (fn new-bid [{:keys [i_id
                        u_id
                        i_buyer_id
                        bid
                        max_bid
                        ;; pass in from ctr rather than select-max+1 so ctr gets incremented
                        new_bid_id
                        ;; 'current timestamp'
                        now]}]
     (let [;; current max bid id
           {:keys [imb imb_ib_id]}
           (-> (q '(from :item-max-bid [{:xt/id imb, :imb_i_id $iid} imb_ib_id])
                  {:args {:iid i_id}})
               first)
           ;;_ (println res)


           ;; current number of bids
           {:keys [i nbids]}
           (-> (q '(from :item [{:xt/id i, :i_id $iid, :i_num_bids nbids}])
                  {:args {:iid i_id}})
               first)
           ;;_ (println res)

           ;; current bid/max
           {:keys [curr_bid, curr_max]}
           (when imb_ib_id
             (-> (q '(from :item-bid [{:ib_id $imb_ib_id, :ib_bid curr_bid, :ib_max_bid curr_max}])
                    {:args {:imb_ib_id imb_ib_id}})
                 first))

           ;;_ (println res)

           new_bid_win (or (nil? imb_ib_id) (< curr_max max_bid))
           new_bid (if (and new_bid_win curr_max (< bid curr_max) curr_max) curr_max bid)
           upd_curr_bid (and curr_bid (not new_bid_win) (< curr_bid bid))
           composite-id-fn (fn [& ids] (apply str (butlast (interleave ids (repeat "-")))))]
       (cond-> []
         ;; increment number of bids on item
         i
         (conj (xt/put :item (assoc (first (q '~item-query {:args {:i i}, :key-fn :snake_case}))
                                    :i_num_bids (inc nbids))))

         ;; if new bid exceeds old, bump it
         upd_curr_bid
         (conj (xt/put :item-max-bid (assoc (first (q '~item-max-bid-query {:args {:imb imb}, :key-fn :snake_case}))
                                            :imb_bid bid)))

         ;; we exceed the old max, win the bid.
         (and curr_bid new_bid_win)
         (conj (xt/put :item-max-bid (assoc (first (q '~item-max-bid-query {:args {:imb imb}, :key-fn :snake_case}))
                                            :imb_ib_id new_bid_id
                                            :imb_ib_u_id u_id
                                            :imb_updated now)))

         ;; no previous max bid, insert new max bid
         (nil? imb_ib_id)
         (conj (xt/put :item-max-bid {:xt/id (composite-id-fn new_bid_id i_id)
                                      :imb_i_id i_id
                                      :imb_u_id u_id
                                      :imb_ib_id new_bid_id
                                      :imb_ib_i_id i_id
                                      :imb_ib_u_id u_id
                                      :imb_created now
                                      :imb_updated now}))

         :always
         ;; add new bid
         (conj (xt/put :item-bid {:xt/id new_bid_id
                                  :ib_id new_bid_id
                                  :ib_i_id i_id
                                  :ib_u_id u_id
                                  :ib_buyer_id i_buyer_id
                                  :ib_bid new_bid
                                  :ib_max_bid max_bid
                                  :ib_created_at now
                                  :ib_updated now})))))))

(defn- sample-category-id [worker]
  (if-some [weighting (::category-weighting (:custom-state worker))]
    (weighting (b2/rng worker))
    (b2/sample-gaussian worker category-id)))

(defn sample-status [worker]
  (nth [:open :waiting-for-purchase :closed] (mod (.nextInt ^Random (b2/rng worker)) 3)))

(defn proc-new-item
  "Insert a new ITEM record for a user.

  The benchmark client provides all the preliminary information required for the new item, as well as optional information to create derivative image and attribute records.
  After inserting the new ITEM record, the transaction then inserts any GLOBAL ATTRIBUTE VALUE and ITEM IMAGE.

  After these records are inserted, the transaction then updates the USER record to add the listing fee to the seller’s balance.

  The benchmark randomly selects id from a pool of users as an input for u_id parameter using Gaussian distribution. A c_id parameter is randomly selected using a flat histogram from the real auction site’s item category statistic."
  [worker]
  (let [i_id-raw (.getAndIncrement (b2/counter worker item-id))
        i_id (item-id i_id-raw)
        u_id (b2/sample-gaussian worker user-id)
        c_id (sample-category-id worker)
        name (b2/random-str worker)
        description (b2/random-str worker)
        initial-price (random-price worker)
        attributes (b2/random-str worker)
        gag-ids (remove nil? (b2/random-seq worker {:min 0, :max 16, :unique true} b2/sample-flat global-attribute-group-id))
        gav-ids (remove nil? (b2/random-seq worker {:min 0, :max 16, :unique true} b2/sample-flat global-attribute-value-id))
        images (b2/random-seq worker {:min 0, :max 16, :unique true} b2/random-str)
        start-date (b2/current-timestamp worker)
        ;; up to 42 days
        end-date (.plusSeconds ^Instant start-date (* 60 60 24 (* (inc (.nextInt (b2/rng worker) 42)))))
        ;; append attribute names to desc
        description-with-attributes
        (->> (xt/q (:sut worker)
                   '(unify (from :gag [{:xt/id gag-id} gag-name])
                           (from :gav [{:xt/id gav-id, :gav-gag-id gag-id} gav-name])
                           ;; TODO exprs in unnest #3026
                           (with {gag-ids $gag-ids, gav-ids $gav-ids})
                           (unnest {gag-id gag-ids})
                           (unnest {gav-id gav-ids}))
                   {:args {:gag-ids gag-ids, :gav-ids gav-ids}, :key-fn :snake_case})
             (str/join " ")
             (str description " "))]

    (->> (concat
          [(xt/put :item
                   {:xt/id i_id
                    :i_id i_id
                    :i_u_id u_id
                    :i_c_id c_id
                    :i_name name
                    :i_description description-with-attributes
                    :i_user_attributes attributes
                    :i_initial_price initial-price
                    :i_num_bids 0
                    :i_num_images (count images)
                    :i_num_global_attrs (count gav-ids)
                    :i_start_date start-date
                    :i_end_date end-date
                    :i_status :open})]
          (for [[i image] (map-indexed vector images)
                :let [ii_id (bit-or (bit-shift-left i 60) (bit-and i_id-raw 0x0FFFFFFFFFFFFFFF))]]
            (xt/put :item-comment
                    {:xt/id (str "ii_" ii_id)
                     :ii_id ii_id
                     :ii_i_id i_id
                     :ii_u_id u_id
                     :ii_path image}))
          ;; fix BitVector metadata issue
          (when u_id [(-> (xt/update-table :user {:bind [{:xt/id $uid} u_balance]
                                                  :set {:u_balance (- u_balance 1)}})
                          (xt/with-op-args {:uid u_id}))]))
         (xt/submit-tx (:sut worker)))))

;; represents a probable state of an item that can be sampled randomly
(defrecord ItemSample [i_id, i_u_id, i_status, i_end_date, i_num_bids])

(defn item-status-groups [node]
  (let [items (xt/q node '(from :item [{:xt/id i} i_id i_u_id i_status i_end_date i_num_bids])
                    {:key-fn :snake_case})
        all (ArrayList.)
        open (ArrayList.)
        ending-soon (ArrayList.)
        waiting-for-purchase (ArrayList.)
        closed (ArrayList.)]
    (doseq [{:keys [i_id i_u_id i_status ^Instant i_end_date i_num_bids]} items
            :let [projected-status i_status #_(project-item-status i_status i_end_date i_num_bids now)

                  ^ArrayList alist
                  (case projected-status
                    :open open
                    :closed closed
                    :waiting-for-purchase waiting-for-purchase
                    :ending-soon ending-soon
                    ;; TODO debug why this happens
                    nil)

                  item-sample (->ItemSample i_id i_u_id i_status i_end_date i_num_bids)]]

      (.add all item-sample)
      (when alist
        (.add alist item-sample)))
    {:all (vec all)
     :open (vec open)
     :ending-soon (vec ending-soon)
     :waiting-for-purchase (vec waiting-for-purchase)
     :closed (vec closed)}))

;; do every now and again to provide inputs for item-dependent computations
(defn index-item-status-groups [worker]
  (let [{:keys [sut, ^ConcurrentHashMap custom-state]} worker
        node sut
        res (item-status-groups node)]
    (.putAll custom-state {:item-status-groups res #_(item-status-groups node now)})
    #_(with-open [db (xt/open-db sut)]
        (.putAll custom-state {:item-status-groups (item-status-groups db now)}))))

(defn largest-id [node table prefix-length]
  (let [id (->> (xt/q node (xt/template (from ~table [{:xt/id id}])))
                (sort-by :id  #(cond (< (count %1) (count %2)) 1
                                     (< (count %2) (count %1)) -1
                                     :else (compare %2 %1)))
                first
                :id)]
    (when id
      (parse-long (subs id prefix-length)))))

(defn load-stats-into-worker [{:keys [sut] :as worker}]
  (index-item-status-groups worker)
  (log/info "query for user")
  (b2/set-domain worker user-id (or (largest-id sut :user 2) 0))
  (log/info "query for region")
  (b2/set-domain worker region-id (or (largest-id sut :region 2) 0))
  (log/info "query for item")
  (b2/set-domain worker item-id (or (largest-id sut :item 2) 0))
  (log/info "query for item-bid")
  (b2/set-domain worker item-bid-id (or (largest-id sut :item-bid 3) 0))
  (log/info "query for category")
  (b2/set-domain worker category-id (or (largest-id sut :category 2) 0))
  (log/info "query for gag")
  (b2/set-domain worker gag-id (or (largest-id sut :gag 4) 0))
  (log/info "query for gav")
  (b2/set-domain worker gav-id (or (largest-id sut :gav 4) 0)))

(defn log-stats [worker]
  (log/info "#user " (.get (b2/counter worker user-id)))
  (log/info "#region " (.get (b2/counter worker region-id)))
  (log/info "#item " (.get (b2/counter worker item-id)))
  (log/info "#item-bid " (.get (b2/counter worker item-bid-id)))
  (log/info "#category " (.get (b2/counter worker category-id)))
  (log/info "#gag " (.get (b2/counter worker gag-id)))
  (log/info "#gav " (.get (b2/counter worker gav-id))))

(defn random-item [worker & {:keys [status] :or {status :all}}]
  (let [isg (-> worker :custom-state :item-status-groups (get status) vec)
        item (b2/random-nth worker isg)]
    item))

(defn add-item-status [{:keys [^ConcurrentHashMap custom-state]}
                       {:keys [i_status] :as item-sample}]
  (.putAll custom-state {:item-status-groups (-> custom-state :item-status-groups
                                                 (update :all (fnil conj []) item-sample)
                                                 (update i_status (fnil conj []) item-sample))}))

(defn generate-new-bid-params [worker]
  (let [{:keys [i_id, i_u_id]} (random-item worker :status :open)
        i_buyer_id (b2/sample-gaussian worker user-id)]
    (if (and i_buyer_id (= i_buyer_id i_u_id))
      (generate-new-bid-params worker)
      {:i_id i_id,
       :u_id i_u_id,
       :i_buyer_id i_buyer_id
       :bid (random-price worker)
       :max_bid (random-price worker)
       :new_bid_id (b2/increment worker item-bid-id)
       :now (b2/current-timestamp worker)})))

(defn proc-new-bid [worker]
  (let [params (generate-new-bid-params worker)]
    (when (and (:i_id params) (:u_id params))
      (xt/submit-tx (:sut worker) [(xt/call :new-bid params)]))))

(defn proc-get-item [worker]
  (let [{:keys [sut]} worker
        ;; the benchbase project uses a profile that keeps item pairs around
        ;; selects only closed items for a particular user profile (they are sampled together)
        ;; right now this is a totally random sample with one less join than we need.
        {:keys [i_id]} (random-item worker :status :open)
        ;; _ (log/info "id:" i_id)
        ;; i_id (b2/sample-flat worker item-id)
        ]
    (xt/q sut '(-> (from :item [{:xt/id i_id, :i_status :open}
                                i_u_id i_initial_price i_current_price])
                   (where (= $iid i_id)))
          {:args {:iid i_id}, :key-fn :snake_case})))

(defn read-category-tsv []
  (let [cat-tsv-rows
        (with-open [rdr (io/reader (io/resource "data/auctionmark/auctionmark-categories.tsv"))]
          (vec (for [line (line-seq rdr)
                     :let [split (str/split line #"\t")
                           cat-parts (butlast split)
                           item-count (last split)
                           parts (remove str/blank? cat-parts)]]
                 {:parts (vec parts)
                  :item-count (parse-long item-count)})))
        extract-cats
        (fn extract-cats [parts]
          (when (seq parts)
            (cons parts (extract-cats (pop parts)))))
        all-paths (into #{} (comp (map :parts) (mapcat extract-cats)) cat-tsv-rows)
        path-i (into {} (map-indexed (fn [i x] [x i])) all-paths)
        trie (reduce #(assoc-in %1 (:parts %2) (:item-count %2)) {} cat-tsv-rows)
        trie-node-item-count (fn trie-node-item-count [path]
                               (let [n (get-in trie path)]
                                 (if (integer? n)
                                   n
                                   (reduce + 0 (map trie-node-item-count (keys n))))))]
    (->> (for [[path i] path-i]
           [(category-id i)
            {:i i
             :xt/id (category-id i)
             :category-name (str/join "/" path)
             :parent (category-id (path-i i))
             :item-count (trie-node-item-count path)}])
         (into {}))))

(defn load-categories-tsv [worker]
  (let [cats (read-category-tsv)
        {:keys [^ConcurrentHashMap custom-state]} worker]
    ;; squirrel these data-structures away for later (see category-generator, sample-category-id)
    (.putAll custom-state {::categories cats
                           ::category-weighting (b2/weighted-sample-fn (map (juxt :xt/id :item-count) (vals cats)))})))

(defn generate-region [worker]
  (let [r-id (b2/increment worker region-id)]
    {:xt/id r-id
     :r_id r-id
     :r_name (b2/random-str worker 6 32)}))

(defn generate-global-attribute-group [worker]
  (let [gag-id (b2/increment worker gag-id)
        category-id (b2/sample-flat worker category-id)]
    {:xt/id gag-id
     :gag_c_id category-id
     :gag_name (b2/random-str worker 6 32)}))

(defn generate-global-attribute-value [worker]
  (let [gav-id (b2/increment worker gav-id)
        gag-id (b2/sample-flat worker gag-id)]
    {:xt/id gav-id
     :gav_gag_id gag-id
     :gav_name (b2/random-str worker 6 32)}))

(defn generate-category [worker]
  (let [{::keys [categories]} (:custom-state worker)
        c-id (b2/increment worker category-id)
        {:keys [category-name, parent]} (categories c-id)]
    {:xt/id c-id
     :c_id c-id
     :c_parent_id (when (seq parent) (:xt/id (categories parent)))
     :c_name (or category-name (b2/random-str worker 6 32))}))

(defn generate-user-attributes [worker]
  (let [u_id (b2/sample-flat worker user-id)
        ua-id (b2/increment worker user-attribute-id)]
    (when u_id
      {:xt/id ua-id
       :ua_u_id u_id
       :ua_name (b2/random-str worker 5 32)
       :ua_value (b2/random-str worker 5 32)
       :u_created (b2/current-timestamp worker)})))

(defn generate-item [worker]
  (let [i_id (b2/increment worker item-id)
        i_u_id (b2/sample-flat worker user-id)
        i_c_id (sample-category-id worker)
        i_start_date (b2/current-timestamp worker)
        i_end_date (.plus ^Instant (b2/current-timestamp worker) (Duration/ofDays 32))
        i_status (sample-status worker)]
    (add-item-status worker (->ItemSample i_id i_u_id i_status i_end_date 0))
    (when i_u_id
      {:xt/id i_id
       :i_id i_id
       :i_u_id i_u_id
       :i_c_id i_c_id
       :i_name (b2/random-str worker 6 32)
       :i_description (b2/random-str worker 50 255)
       :i_user_attributes (b2/random-str worker 20 255)
       :i_initial_price (random-price worker)
       :i_current_price (random-price worker)
       :i_num_bids 0
       :i_num_images 0
       :i_num_global_attrs 0
       :i_start_date i_start_date
       :i_end_date i_end_date
       #_(.plus ^Instant (b2/current-timestamp worker) (Duration/ofDays 32))
       :i_status i_status})))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- wrap-in-logging [f]
  (fn [& args]
    (log/trace (str "Start of " f))
    (let [res (apply f args)]
      (log/trace (str "Finish of " f))
      res)))

(defn- wrap-in-catch [f]
  (fn [& args]
    (try
      (apply f args)
      (catch Throwable t
        (log/error t (str "Error while executing " f))
        (throw t)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn benchmark [{:keys [seed,
                         threads,
                         duration
                         scale-factor
                         load-phase
                         sync]
                  :or {seed 0,
                       threads 8,
                       duration "PT30S"
                       scale-factor 0.1
                       load-phase true
                       sync false}}]
  (let [duration (Duration/parse duration)
        sf scale-factor]
    (log/trace {:scale-factor scale-factor})
    {:title "Auction Mark OLTP"
     :seed seed
     :tasks
     (into (if load-phase
             [{:t :do
               :stage :load
               :tasks [{:t :call, :f (fn [_] (log/info "start load stage"))}
                       {:t :call, :f [bxt2/install-tx-fns {:new-bid tx-fn-new-bid}]}
                       {:t :call, :f load-categories-tsv}
                       {:t :call, :f [bxt2/generate :region generate-region 75]}
                       {:t :call, :f [bxt2/generate :category generate-category 16908]}
                       {:t :call, :f [bxt2/generate :user generate-user (* sf 1e6)]}
                       {:t :call, :f [bxt2/generate :user-attribute generate-user-attributes (* sf 1e6 1.3)]}
                       {:t :call, :f [bxt2/generate :item generate-item (* sf 1e6 10)]}
                       {:t :call, :f [bxt2/generate :gag generate-global-attribute-group 100]}
                       {:t :call, :f [bxt2/generate :gav generate-global-attribute-value 1000]}
                       {:t :call, :f (fn [_] (log/info "finished load stage"))}]}]

             [])
           [{:t :do
             :stage :setup-worker
             :tasks [{:t :call, :f (fn [_] (log/info "setting up worker with stats"))}
                     ;; wait for node to catch up
                     {:t :call, :f #(when-not load-phase
                                      ;; otherwise nothing has come through the log yet
                                      (Thread/sleep 1000)
                                      #_(tu/then-await-tx (:sut %)))}
                     {:t :call, :f load-stats-into-worker}
                     {:t :call, :f log-stats}
                     {:t :call, :f (fn [_] (log/info "finished setting up worker with stats"))}]}

            {:t :concurrently
             :stage :oltp
             :duration duration
             :join-wait (Duration/ofSeconds 5)
             :thread-tasks [{:t :pool
                             :duration duration
                             :join-wait (Duration/ofMinutes 5)
                             :thread-count threads
                             :think Duration/ZERO
                             :pooled-task {:t :pick-weighted
                                           :choices [[{:t :call, :transaction :get-item, :f (wrap-in-catch proc-get-item)} 12.0]
                                                     [{:t :call, :transaction :new-user, :f (wrap-in-catch proc-new-user)} 0.5]
                                                     [{:t :call, :transaction :new-item, :f (wrap-in-catch proc-new-item)} 1.0]
                                                     [{:t :call, :transaction :new-bid,  :f (wrap-in-catch proc-new-bid)} 2.0]]}}
                            {:t :freq-job
                             :duration duration
                             :freq (Duration/ofMillis (* 0.2 (.toMillis duration)))
                             :job-task {:t :call, :transaction :index-item-status-groups, :f (wrap-in-catch index-item-status-groups)}}]}
            (when sync {:t :call, :f #(tu/then-await-tx (:sut %))})])}))
