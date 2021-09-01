(ns crux.pull-test
  (:require [clojure.test :as t]
            [crux.api :as crux]
            [crux.fixtures :as fix :refer [*api*]]
            [crux.pull :as pull]
            [clojure.java.io :as io]))

(t/use-fixtures :each fix/with-node)

(defn- submit-bond []
  (fix/submit+await-tx (for [doc (read-string (slurp (io/resource "data/james-bond.edn")))]
                         [:crux.tx/put doc]))
  (crux/db *api*))

(def ->lookup-docs
  (let [f @#'pull/lookup-docs]
    (fn [!lookup-counts]
      (fn [v db]
        (swap! !lookup-counts conj (count (::pull/hashes (meta v))))
        (f v db)))))

(t/deftest test-pull
  (let [db (submit-bond)]

    (t/is (= #{}
             (crux/q db '{:find [(pull ?v [])]
                          :where [[?v :not/here "N/A"]]})))

    (t/is (= #{[nil]}
             (crux/q db '{:find [(pull ?v [])]
                          :where [[?v :vehicle/brand "Aston Martin"]]})))

    (t/testing "simple props"
      (let [expected #{[{:vehicle/brand "Aston Martin", :vehicle/model "DB5"}]
                       [{:vehicle/brand "Aston Martin", :vehicle/model "DB10"}]
                       [{:vehicle/brand "Aston Martin", :vehicle/model "DBS"}]
                       [{:vehicle/brand "Aston Martin", :vehicle/model "DBS V12"}]
                       [{:vehicle/brand "Aston Martin", :vehicle/model "V8 Vantage Volante"}]
                       [{:vehicle/brand "Aston Martin", :vehicle/model "V12 Vanquish"}]}]
        (let [!lookup-counts (atom [])]
          (with-redefs [pull/lookup-docs (->lookup-docs !lookup-counts)]
            (t/is (= expected
                     (crux/q db '{:find [(pull ?v [:vehicle/brand :vehicle/model])]
                                  :where [[?v :vehicle/brand "Aston Martin"]]})))
            (t/is (= [6] @!lookup-counts) "batching lookups")))

        (let [!lookup-counts (atom [])]
          (with-redefs [pull/lookup-docs (->lookup-docs !lookup-counts)]
            (t/is (= expected
                     (crux/q db '{:find [(pull ?v [:vehicle/brand :vehicle/model])]
                                  :where [[?v :vehicle/brand "Aston Martin"]]
                                  :batch-size 3})))
            (t/is (= [3 3] @!lookup-counts) "batching lookups")))))

    (t/testing "renames"
      (t/is (= #{[{:brand "Aston Martin", :model "DB5"}]
                 [{:brand "Aston Martin", :model "DB10"}]
                 [{:brand "Aston Martin", :model "DBS"}]
                 [{:brand "Aston Martin", :model "DBS V12"}]
                 [{:brand "Aston Martin", :model "V8 Vantage Volante"}]
                 [{:brand "Aston Martin", :model "V12 Vanquish"}]}

               (crux/q db '{:find [(pull ?v [(:vehicle/brand {:as :brand})
                                                    (:vehicle/model {:as :model})])]
                            :where [[?v :vehicle/brand "Aston Martin"]]}))))

    (t/testing "forward joins"
      (let [!lookup-counts (atom [])]
        (with-redefs [pull/lookup-docs (->lookup-docs !lookup-counts)]
          (t/is (= #{[{:film/year "2002",
                       :film/name "Die Another Day"
                       :film/bond {:person/name "Pierce Brosnan"},
                       :film/director {:person/name "Lee Tamahori"},
                       :film/vehicles #{{:vehicle/brand "Jaguar", :vehicle/model "XKR"}
                                        {:vehicle/brand "Aston Martin", :vehicle/model "V12 Vanquish"}
                                        {:vehicle/brand "Ford", :vehicle/model "Thunderbird"}
                                        {:vehicle/brand "Ford", :vehicle/model "Fairlane"}}}]}
                   (crux/q db '{:find [(pull ?f [{:film/bond [:person/name]}
                                                        {:film/director [:person/name]}
                                                        {(:film/vehicles {:into #{}}) [:vehicle/brand :vehicle/model]}
                                                        :film/name :film/year])]
                                :where [[?f :film/name "Die Another Day"]]})))
          (t/is (= [1 6] @!lookup-counts) "batching lookups"))))

    (t/testing "reverse joins"
      (let [!lookup-counts (atom [])]
        (with-redefs [pull/lookup-docs (->lookup-docs !lookup-counts)]
          (t/is (= #{[{:person/name "Daniel Craig",
                       :film/_bond #{#:film{:name "Skyfall", :year "2012"}
                                     #:film{:name "Spectre", :year "2015"}
                                     #:film{:name "Casino Royale", :year "2006"}
                                     #:film{:name "Quantum of Solace", :year "2008"}}}]}
                   (crux/q db '{:find [(pull ?dc [:person/name
                                                         {(:film/_bond {:into #{}}) [:film/name :film/year]}])]
                                :where [[?dc :person/name "Daniel Craig"]]})))
          (t/is (= [5] @!lookup-counts) "batching lookups"))))

    (t/testing "reverse joins, rename"
      (t/is (= #{[{:person/name "Daniel Craig",
                   :films [#:film{:name "Skyfall", :year "2012"}
                           #:film{:name "Spectre", :year "2015"}
                           #:film{:name "Casino Royale", :year "2006"}
                           #:film{:name "Quantum of Solace", :year "2008"}]}]}
               (crux/q db '{:find [(pull ?dc [:person/name
                                                     {(:film/_bond {:as :films}) [:film/name :film/year]}])]
                            :where [[?dc :person/name "Daniel Craig"]]}))))

    (t/testing "pull *"
      (t/is (= #{[{:crux.db/id :daniel-craig
                   :person/name "Daniel Craig",
                   :type :person}]}
               (crux/q db '{:find [(pull ?dc [*])]
                            :where [[?dc :person/name "Daniel Craig"]]}))))

    (t/testing "pull fn"
      (t/is (= #:film{:name "Spectre", :year "2015"}
               (crux/pull db (pr-str [:film/name :film/year]) :spectre)))
      (t/is (= #:film{:name "Spectre", :year "2015"}
               (crux/pull db [:film/name :film/year] :spectre))))

    (t/testing "pullMany fn"
      (t/is (= #{#:film{:name "Skyfall", :year "2012"}
                 #:film{:name "Spectre", :year "2015"}}
               (set (crux/pull-many db (pr-str [:film/name :film/year]) #{:skyfall :spectre}))))

      (t/is (= #{#:film{:name "Skyfall", :year "2012"}
                 #:film{:name "Spectre", :year "2015"}}
               (set (crux/pull-many db [:film/name :film/year] #{:skyfall :spectre})))))

    (t/testing "pullMany fn vector"
      (t/is (= [#:film {:name "Skyfall", :year "2012"}
                #:film {:name "Spectre", :year "2015"}]
               (crux/pull-many db (pr-str [:film/name :film/year]) #{:skyfall :spectre})))

      (t/is (= [#:film {:name "Skyfall", :year "2012"}
                #:film {:name "Spectre", :year "2015"}]
               (crux/pull-many db [:film/name :film/year] #{:skyfall :spectre}))))))

(t/deftest test-limit
  (let [db (submit-bond)]
    (t/testing "props"
      (t/is (= #{[{:film/name "Die Another Day"
                   :film/vehicles #{:xkr :v12-vanquish}}]}
               (crux/q db '{:find [(pull ?f [:film/name (:film/vehicles {:into #{}, :limit 2})])]
                            :where [[?f :film/name "Die Another Day"]]}))))

    (t/testing "forward joins"
      (let [!lookup-counts (atom [])]
        (with-redefs [pull/lookup-docs (->lookup-docs !lookup-counts)]
          (t/is (= #{[{:film/year "2002",
                       :film/name "Die Another Day"
                       :film/bond {:person/name "Pierce Brosnan"},
                       :film/director {:person/name "Lee Tamahori"},
                       :film/vehicles #{{:vehicle/brand "Jaguar", :vehicle/model "XKR"}
                                        {:vehicle/brand "Aston Martin", :vehicle/model "V12 Vanquish"}}}]}
                   (crux/q db '{:find [(pull ?f [{:film/bond [:person/name]}
                                                        {:film/director [:person/name]}
                                                        {(:film/vehicles {:into #{}, :limit 2}) [:vehicle/brand :vehicle/model]}
                                                        :film/name :film/year])]
                                :where [[?f :film/name "Die Another Day"]]})))
          (t/is (= [1 4] @!lookup-counts) "batching lookups"))))

    (t/testing "reverse joins"
      (let [!lookup-counts (atom [])]
        (with-redefs [pull/lookup-docs (->lookup-docs !lookup-counts)]
          (t/is (= #{[{:person/name "Daniel Craig",
                       :film/_bond #{#:film{:name "Skyfall", :year "2012"}
                                     #:film{:name "Spectre", :year "2015"}}}]}
                   (crux/q db '{:find [(pull ?dc [:person/name
                                                         {(:film/_bond {:into #{}, :limit 2}) [:film/name :film/year]}])]
                                :where [[?dc :person/name "Daniel Craig"]]})))
          (t/is (= [3] @!lookup-counts) "batching lookups"))))))

(t/deftest test-union
  (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :foo
                                       :type :a
                                       :x 2
                                       :y "this"
                                       :z :not-this}]
                        [:crux.tx/put {:crux.db/id :bar
                                       :type :b
                                       :y "not this"
                                       :z 5}]])

  (t/is (= #{[{:crux.db/id :foo, :x 2, :y "this"}]
             [{:crux.db/id :bar, :z 5}]}
           (crux/q (crux/db *api*)
                   '{:find [(pull ?it [{:type {:a [:x :y], :b [:z]}}
                                              :crux.db/id])]
                     :where [[?it :crux.db/id]]}))))

(t/deftest test-recursive
  (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :root}]
                        [:crux.tx/put {:crux.db/id :a
                                       :parent :root}]
                        [:crux.tx/put {:crux.db/id :b
                                       :parent :root}]
                        [:crux.tx/put {:crux.db/id :aa
                                       :parent :a}]
                        [:crux.tx/put {:crux.db/id :ab
                                       :parent :a}]
                        [:crux.tx/put {:crux.db/id :aba
                                       :parent :ab}]
                        [:crux.tx/put {:crux.db/id :abb
                                       :parent :ab}]])

  (t/testing "forward unbounded recursion"
    (t/is (= {:crux.db/id :aba
              :parent {:crux.db/id :ab
                       :parent {:crux.db/id :a
                                :parent {:crux.db/id :root}}}}
             (ffirst (crux/q (crux/db *api*)
                             '{:find [(pull ?aba [:crux.db/id {:parent ...}])]
                               :where [[?aba :crux.db/id :aba]]})))))

  (t/testing "forward bounded recursion"
    (t/is (= {:crux.db/id :aba
              :parent {:crux.db/id :ab
                       :parent {:crux.db/id :a}}}
             (ffirst (crux/q (crux/db *api*)
                             '{:find [(pull ?aba [:crux.db/id {:parent 2}])]
                               :where [[?aba :crux.db/id :aba]]})))))

  (t/testing "reverse unbounded recursion"
    (t/is (= {:crux.db/id :root
              :_parent [{:crux.db/id :a
                         :_parent [{:crux.db/id :aa}
                                   {:crux.db/id :ab
                                    :_parent [{:crux.db/id :aba}
                                              {:crux.db/id :abb}]}]}
                        {:crux.db/id :b}]}
             (ffirst (crux/q (crux/db *api*)
                             '{:find [(pull ?root [:crux.db/id {:_parent ...}])]
                               :where [[?root :crux.db/id :root]]})))))

  (t/testing "reverse bounded recursion"
    (t/is (= {:crux.db/id :root
              :_parent [{:crux.db/id :a
                         :_parent [{:crux.db/id :aa}
                                   {:crux.db/id :ab}]}
                        {:crux.db/id :b}]}
             (ffirst (crux/q (crux/db *api*)
                             '{:find [(pull ?root [:crux.db/id {:_parent 2}])]
                               :where [[?root :crux.db/id :root]]}))))))

(t/deftest test-doesnt-hang-on-unknown-eid
  (t/is (= #{[nil]}
           (crux/q (crux/db *api*)
                   '{:find [(pull ?e [*])]
                     :in [?e]
                     :timeout 500}
                   "doesntexist"))))

(t/deftest test-with-speculative-doc-store
  (let [db (crux/with-tx (crux/db *api*) [[:crux.tx/put {:crux.db/id :foo}]])]
    (t/is (= #{[{:crux.db/id :foo}]}
             (crux/q db
                     '{:find [(pull ?e [*])]
                       :where [[?e :crux.db/id :foo]]})))))

(t/deftest test-missing-forward-join
  (fix/submit+await-tx [[:crux.tx/put {:crux.db/id :foo :ref [:bar :baz]}]
                        [:crux.tx/put {:crux.db/id :bar}]])

  (t/is (= #{[{:ref [#:crux.db{:id :bar} nil]}]}
           (crux/q (crux/db *api*)
                   '{:find [(pull ?it [{:ref [:crux.db/id]}])]
                     :where [[?it :crux.db/id :foo]]}))))
