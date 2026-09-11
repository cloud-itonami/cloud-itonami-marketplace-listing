(ns listingops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [listingops.advisor :as advisor]
            [listingops.governor :as governor]
            [listingops.store :as store]))

(def now "2026-06-01T00:00:00Z")
(def ctx {:actor-id "listing-actor" :phase 3 :now now})

(defn- db [] (store/seed-db))

(defn- listing-id-of [st seller]
  (->> (store/all-listing-records st)
       (filter #(= seller (:listing/seller %)))
       first :listing/id))

(defn- advise [op & [req]]
  (advisor/-advise (advisor/mock-advisor) nil (merge {:op op} req)))

(defn- check [st op & [req]]
  (governor/check (merge {:op op} req) ctx (advise op req) st))

;; ───────────────────────── seller ground truth ─────────────────────────

(deftest publishing-for-an-unsellable-seller-is-a-hard-block
  (testing "merchant.gamma is identity-verified but NOT payout-bound, so
            marketplace.seller/sellable? refuses — this actor does not
            second-guess that, it consumes it"
    (let [st (db)
          lid (listing-id-of st "merchant.gamma")
          v (check st :publish-listing {:listing-id lid})]
      (is (true? (:hard? v)))
      (is (some #{:admission-refused} (mapv :rule (:violations v))))
      (is (some #{:seller-not-sellable}
                (:reasons (first (:violations v))))))))

(deftest publishing-for-a-sellable-seller-is-clean
  (let [st (db)
        lid (listing-id-of st "merchant.alpha")
        v (check st :publish-listing {:listing-id lid})]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v))
        "a clean publish on an already-admitted seller may auto-commit —
         the self-serve affordance")))

;; ───────────────────────── policy is operator-supplied ─────────────────────────

(deftest restricted-category-is-a-hard-block-and-comes-from-the-store
  (let [st (db)
        lid (listing-id-of st "merchant.alpha")]
    (is (true? (:ok? (check st :publish-listing {:listing-id lid})))
        "beverages is fine under the default policy")
    (store/with-policy st (assoc (store/policy st) :restricted #{:beverages}))
    (let [v (check st :publish-listing {:listing-id lid})]
      (is (true? (:hard? v)))
      (is (some #{:restricted-category} (:reasons (first (:violations v)))))
      (testing "the governor read the operator's CONFIGURED policy, not a constant"
        (is (contains? (:restricted (store/policy st)) :beverages))))))

(deftest counterfeit-signal-refuses-display-without-finding-guilt
  (let [st (db)
        l (first (filter #(= "merchant.alpha" (:listing/seller %))
                         (store/all-listing-records st)))]
    (store/with-policy st (assoc (store/policy st)
                                 :counterfeit-flagged #{(:listing/offer l)}))
    (let [v (check st :publish-listing {:listing-id (:listing/id l)})]
      (is (true? (:hard? v)))
      (is (some #{:counterfeit-signal} (:reasons (first (:violations v)))))
      (testing "and the actor records no finding about the seller"
        (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))))))))

(deftest missing-attestation-escalates-rather-than-blocking
  (testing "marketplace.listing's three-valued outcome is preserved:
            :refused means never, :review means a human may decide.
            Flattening both into a block would make the distinction
            pointless"
    (let [st (db)
          l (first (filter #(= "merchant.beta" (:listing/seller %))
                           (store/all-listing-records st)))]
      (store/with-policy st (assoc (store/policy st)
                                   :require #{:authentic-goods :right-to-sell :export-eligible}))
      (let [v (check st :publish-listing {:listing-id (:listing/id l)})]
        (is (false? (:hard? v)) "not a block")
        (is (true? (:high-stakes? v)))
        (is (true? (:escalate? v)))
        (is (false? (:ok? v)))))))

;; ───────────────────────── structural checks ─────────────────────────

(deftest drafting-against-an-unknown-offer-is-a-hard-block
  (let [v (check (db) :draft-listing
                 {:patch {:offer "offer.nonexistent" :product "gtin.05449000000996"
                          :seller "merchant.alpha" :title "X" :category :beverages
                          :images ["https://example.test/x.jpg"]}})]
    (is (true? (:hard? v)))
    (is (some #{:offer-unknown} (mapv :rule (:violations v))))))

(deftest publishing-an-unknown-listing-is-a-hard-block
  (let [v (check (db) :publish-listing {:listing-id "listing.nope"})]
    (is (true? (:hard? v)))
    (is (some #{:listing-unknown} (mapv :rule (:violations v))))))

(deftest effect-must-be-propose
  (let [st (db)
        lid (listing-id-of st "merchant.alpha")
        v (governor/check {:op :publish-listing :listing-id lid} ctx
                          (assoc (advise :publish-listing {:listing-id lid}) :effect :commit)
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:effect-not-propose} (mapv :rule (:violations v))))))

(deftest op-outside-the-allowlist-is-a-scope-violation
  (let [v (governor/check {:op :delete-seller} ctx
                          {:op :delete-seller :effect :propose :confidence 0.99}
                          (db))]
    (is (true? (:hard? v)))
    (is (some #{:op-not-allowed} (mapv :rule (:violations v))))))

(deftest scope-exclusion-blocks-determination-claims
  (let [st (db)
        lid (listing-id-of st "merchant.alpha")
        p (advisor/infer nil {:op :suppress-listing :listing-id lid
                              :patch {:reason "report"} :out-of-scope? true})
        v (governor/check {:op :suppress-listing :listing-id lid} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:scope-excluded} (mapv :rule (:violations v))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "legitimate suppression proposals must talk about counterfeit
            signals and restricted categories — the excluded terms are
            phrased as DETERMINATIONS so the happy path never self-blocks"
    (let [st (db)
          lid (listing-id-of st "merchant.alpha")]
      (doseq [[op req] [[:publish-listing {:listing-id lid}]
                        [:suppress-listing {:listing-id lid :patch {:reason "偽造品の通報あり"}}]
                        [:reindex-catalog {}]
                        [:flag-listing-concern {:listing-id lid :patch {:concern "規制カテゴリの疑い"}}]]]
        (let [v (check st op req)]
          (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))) (str op)))))))

;; ───────────────────────── escalation ─────────────────────────

(deftest listing-concern-always-escalates
  (let [st (db)
        lid (listing-id-of st "merchant.alpha")
        v (check st :flag-listing-concern {:listing-id lid :patch {:confidence 0.99}})]
    (is (false? (:hard? v)))
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v)))))

(deftest suppression-is-always-cheap-and-clean
  (testing "the correction path must be at least as fast as the mistake"
    (let [st (db)
          lid (listing-id-of st "merchant.alpha")
          v (check st :suppress-listing {:listing-id lid :patch {:reason "seller request"}})]
      (is (false? (:hard? v)))
      (is (true? (:ok? v))))))

(deftest low-confidence-escalates
  (let [st (db)
        lid (listing-id-of st "merchant.alpha")
        v (governor/check {:op :publish-listing :listing-id lid} ctx
                          (assoc (advise :publish-listing {:listing-id lid}) :confidence 0.2)
                          st)]
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))))

(deftest expired-credential-blocks-publishing
  (testing "the clock comes from context, never from the governor itself"
    (let [st (db)
          lid (listing-id-of st "merchant.alpha")
          future-ctx (assoc ctx :now "2028-01-01T00:00:00Z")
          v (governor/check {:op :publish-listing :listing-id lid} future-ctx
                            (advise :publish-listing {:listing-id lid}) st)]
      (is (true? (:hard? v)))
      (is (some #{:seller-not-sellable} (:reasons (first (:violations v))))))))
