(ns xtdb.remote-api-client-test
  (:require [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.io :as xio]
            [xtdb.remote-api-client :as sut])
  (:import clojure.lang.ExceptionInfo
           com.nimbusds.jose.crypto.ECDSASigner
           com.nimbusds.jose.jwk.gen.ECKeyGenerator
           [com.nimbusds.jose JWSAlgorithm JWSHeader$Builder]
           [com.nimbusds.jose.jwk Curve ECKey JWKSet]
           [com.nimbusds.jwt JWTClaimsSet$Builder SignedJWT]
           [java.util Date]
           [java.util.function Supplier]))

(def ^ECKey ec-key
  (.. (ECKeyGenerator. Curve/P_256) (keyID "123") generate))

(def jwks (str (JWKSet. ec-key)))

(defn ->jwt [{:keys [expiration-time ^ECKey ec-key]
              :or {ec-key ec-key}}]
  (let [jwt (SignedJWT. (.. (JWSHeader$Builder. JWSAlgorithm/ES256)
                            (keyID (.getKeyID ec-key))
                            build)
                        (.. (JWTClaimsSet$Builder.)
                            (subject "alice")
                            (issuer "https://c2id.com")
                            (expirationTime expiration-time)
                            (build)))]
    (.sign jwt (ECDSASigner. ec-key))
    (.serialize jwt)))

(def future-date
  (Date. (+ (.getTime (Date.)) (* 60 1000))))

(def valid-jwt
  (->jwt {:expiration-time future-date}))

(def ^:dynamic *api*)

(defn with-api* [{:keys [jwks ->jwt-token]} f]
  (let [server-port (xio/free-port)]
    (with-open [node (xt/start-node {:xtdb.http-server/server {:port server-port
                                                               :jwks jwks}})
                client (xt/new-api-client (str "http://localhost:" server-port) {:->jwt-token ->jwt-token})]

      (binding [*api* client]
        (f)))))

(defmacro with-api [opts & body]
  `(with-api* ~opts (fn [] ~@body)))

(t/deftest test-unauthenticated-client-and-unauthenticated-server
  (with-api {}
    (let [submitted-tx (xt/submit-tx *api* [[::xt/put {:xt/id :ivan :name "Ivan"}]])]
      (t/is (= submitted-tx (xt/await-tx *api* submitted-tx)))
      (t/is (true? (xt/tx-committed? *api* submitted-tx))))))

(t/deftest test-authenticated-client-and-unauthenticated-server
  (with-api {:->jwt-token (constantly valid-jwt)}

    (let [submitted-tx (xt/submit-tx *api* [[::xt/put {:xt/id :ivan :name "Ivan"}]])]
      (t/is (= submitted-tx (xt/await-tx *api* submitted-tx)))
      (t/is (true? (xt/tx-committed? *api* submitted-tx))))))

(t/deftest test-unauthenticated-client-and-authenticated-server
  (with-api {:jwks jwks}
    (t/is (thrown-with-msg? ExceptionInfo #"HTTP status 401"
                            (xt/status *api*)))))

(t/deftest test-authenticated-client-and-authenticated-server
  (with-api {:jwks jwks, :->jwt-token (constantly valid-jwt)}
    (let [submitted-tx (xt/submit-tx *api* [[::xt/put {:xt/id :ivan :name "Ivan"}]])]
      (t/is (= submitted-tx (xt/await-tx *api* submitted-tx)))
      (t/is (= #{[:ivan]}
               (xt/q (xt/db *api*) '{:find [?e] :where [[?e :name "Ivan"]]}))))))

(t/deftest test-invalid-jwts
  (t/testing "with a dodgy string"
    (with-api {:jwks jwks,
               :->jwt-token (constantly (subs valid-jwt 10))}
      (t/is (thrown-with-msg? Exception #"Invalid JWS header"
                              (xt/status *api*)))))

  (t/testing "with different signing key"
    (with-api {:jwks jwks,
               :->jwt-token (constantly (->jwt {:expiration-time future-date
                                                :ec-key (.. (ECKeyGenerator. Curve/P_256) (keyID "456") generate)}))}
      (t/is (thrown-with-msg? ExceptionInfo #"HTTP status 401"
                              (xt/status *api*))))))

(t/deftest test-caches-and-refreshes-jwt
  (let [expired-date (Date. (- (.getTime (Date.)) (* 60 1000)))
        expired-jwt (->jwt {:expiration-time expired-date})
        !call-count (atom 0)
        ->jwt-token (#'sut/->jwt-token-fn (reify Supplier
                                            (get [_]
                                              (if (= 1 (swap! !call-count inc))
                                                expired-jwt
                                                valid-jwt))))]
    (binding [sut/*now* (constantly (.toInstant (Date. (- (.getTime expired-date) (* 10 1000)))))]
      (t/is (= expired-jwt (->jwt-token)))
      (t/is (= 1 @!call-count))
      ;; not expired yet, cache it
      (t/is (= expired-jwt (->jwt-token)))
      (t/is (= 1 @!call-count)))

    (binding [sut/*now* (constantly (.toInstant (Date. (- (.getTime expired-date) (* 2 1000)))))]
      (t/is (= valid-jwt (->jwt-token)))
      (t/is (= 2 @!call-count)))))

(t/deftest test-accepts-tx-id
  (with-api {}
    (xt/submit-tx *api* [[::xt/put {:xt/id :foo, :version 1}]])
    (xt/submit-tx *api* [[::xt/put {:xt/id :foo, :version 2}]])

    (xt/sync *api*)

    (let [db (xt/db *api* {::xt/tx-id 0})]
      (t/is (= 1 (:version (xt/entity db :foo))))
      (t/is (= 1 (count (xt/entity-history db :foo :asc {}))))

      (t/is (= #{[1]} (xt/q db '{:find [?version]
                                 :where [[?e :version ?version]]}))))

    (let [db (xt/db *api* {::xt/tx-id 1})]
      (t/is (= 1 (count (xt/entity-history db :foo :asc {:start-tx {::xt/tx-id 1}})))))))
