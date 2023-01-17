(ns xtdb.lucene.multi-field-test
  (:require [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.fixtures :as fix :refer [*api* submit+await-tx]]
            [xtdb.fixtures.lucene :as lf]
            [xtdb.lucene :as l])
  (:import org.apache.lucene.queryparser.classic.ParseException))

(t/use-fixtures :each (lf/with-lucene-opts {:indexer 'xtdb.lucene.multi-field/->indexer}) fix/with-node)

(t/deftest test-multi-field-lucene-queries
  (submit+await-tx [[::xt/put {:xt/id :ivan
                               :firstname "Fred"
                               :surname "Smith"}]])

  (with-open [db (xt/open-db *api*)]
    (t/is (seq (xt/q db {:find '[?e]
                         :where '[[(lucene-text-search "firstname: Fred") [[?e]]]]})))
    (t/is (seq (xt/q db {:find '[?e]
                         :where '[[(lucene-text-search "firstname:James OR surname:smith") [[?e]]]]})))
    (t/is (not (seq (xt/q db {:find '[?e]
                              :where '[[(lucene-text-search "firstname:James OR surname:preston") [[?e]]]]}))))))

(t/deftest test-bindings
  (submit+await-tx [[::xt/put {:xt/id :ivan
                               :firstname "Fred"
                               :surname "Smith"}]])

  (with-open [db (xt/open-db *api*)]
    (t/is (seq (xt/q db '{:find [?e]
                          :in [?surname]
                          :where [[(lucene-text-search "surname: %s" ?surname) [[?e]]]]}
                     "Smith")))
    (t/is (seq (xt/q db '{:find [?e]
                          :in [?surname ?firstname]
                          :where [[(lucene-text-search "surname: %s AND firstname: %s" ?surname ?firstname) [[?e]]]]}
                     "Smith" "Fred")))))

(t/deftest test-namespaced-keywords
  (submit+await-tx [[::xt/put {:xt/id :ivan :person/surname "Smith"}]])

  (with-open [db (xt/open-db *api*)]
    ;; QueryParser/escape also works
    (t/is (seq (xt/q db {:find '[?e]
                         :where '[[(lucene-text-search "person\\/surname: Smith") [[?e]]]
                                  [?e :xt/id]]})))))

(t/deftest test-evict
  (let [in-xtdb? (fn []
                   (with-open [db (xt/open-db *api*)]
                     (boolean (seq (xt/q db {:find '[?e]
                                             :where '[[(lucene-text-search "name: Smith") [[?e]]]
                                                      [?e :xt/id]]})))))
        in-lucene-store? (fn []
                           (with-open [search-results (l/search *api* "name: Smith")]
                             (boolean (seq (iterator-seq search-results)))))]

    (submit+await-tx [[::xt/put {:xt/id :ivan :name "Smith"}]])

    (assert (in-xtdb?))
    (assert (in-lucene-store?))

    (submit+await-tx [[::xt/evict :ivan]])

    (t/is (not (in-xtdb?)))
    (t/is (not (in-lucene-store?)))))

(t/deftest test-malformed-query
  (t/is (thrown-with-msg? ParseException #"Cannot parse"
                          (xt/q (xt/db *api*) {:find '[?e]
                                               :where '[[(lucene-text-search "+12!") [[?e]]]
                                                        [?e :xt/id]]}))))

(t/deftest test-use-in-argument
  (submit+await-tx [[::xt/put {:xt/id :ivan
                               :firstname "Fred"
                               :surname "Smith"}]])

  (with-open [db (xt/open-db *api*)]
    (t/is (seq (xt/q db '{:find [?e]
                          :in [?s]
                          :where [[(lucene-text-search ?s) [[?e]]]]}
                     "firstname: Fred")))
    (t/is (not (seq (xt/q db '{:find [?e]
                               :in [?s]
                               :where [[(lucene-text-search ?s) [[?e]]]]}
                          "firstname Fred"))))
    (t/is (seq (xt/q db '{:find [?e]
                          :in [?s]
                          :where [[(lucene-text-search ?s) [[?e]]]]}
                     "firstname:James OR surname:smith")))
    (t/is (thrown-with-msg? IllegalArgumentException #"lucene-text-search query must be String"
                            (xt/q db '{:find  [?v]
                                       :in    [input]
                                       :where [[(lucene-text-search input) [[?e ?v]]]]}
                                  1)))))

(defn build-lucene-multi-field-or-string [kw-fields term-string]
  (apply str
         (interpose " OR " (for [field kw-fields]
                             (str (subs (str field) 1) ":" term-string)))))

(t/deftest test-construct-or-fields-dynamically
  (submit+await-tx [[::xt/put {:xt/id :ivan
                               :firstname "Fred"
                               :surname "Smith"}]])

  (with-open [db (xt/open-db *api*)]
    (t/is (seq (xt/q db '{:find [?e]
                          :in [?s]
                          :where [[(lucene-text-search ?s) [[?e]]]]}
                     (build-lucene-multi-field-or-string [:firstname :surname] "Fre*"))))))

(t/deftest test-uri-as-xtid-1884
  (with-open [node (xt/start-node {:multi {:xtdb/module 'xtdb.lucene/->lucene-store
                                           :analyzer 'xtdb.lucene/->analyzer
                                           :indexer 'xtdb.lucene.multi-field/->indexer}})]
    (->> (xt/submit-tx node [[::xt/put
                              {:xt/id  (new java.net.URI "http://clojuredocs.org/")
                               :firstname "Fred" :surname "Smith"}]])
         (xt/await-tx node))
    (with-open [db (xt/open-db node)]
      (t/is (seq (xt/q db
                       '{:find [?e]
                         :where [[(lucene-text-search "firstname:%s AND surname:%s" "Fred" "Smith"
                                                  {:lucene-store-k :multi}) [[?e]]]]}))))))
