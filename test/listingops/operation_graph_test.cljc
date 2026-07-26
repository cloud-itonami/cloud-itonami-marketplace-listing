(ns listingops.operation-graph-test
  "Integration tests for `listingops.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end, and that the
  buyer surface reflects what the graph actually committed."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [listingops.operation :as operation]
            [listingops.store :as store]
            [listingops.surface :as surface]))

(def now "2026-06-01T00:00:00Z")
(def ^:private op-context {:actor-id "listing-01" :phase 3 :now now})
(def product "gtin.05449000000996")

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- listing-of [st seller]
  (->> (store/all-listing-records st)
       (filter #(= seller (:listing/seller %))) first))

(deftest publish-auto-commits-and-becomes-buyer-visible
  (testing "the self-serve affordance, end to end: a clean publish on an
            already-admitted seller commits with no interrupt AND the
            buyer surface reflects it"
    (let [st (store/seed-db)
          actor (operation/build st)
          gamma (listing-of st "merchant.gamma")]
      ;; gamma cannot publish (not payout-bound), so start from a seller
      ;; who can: suppress alpha then re-publish it.
      (store/commit-record! st {:op :suppress-listing
                                :value {:listing-id (:listing/id (listing-of st "merchant.alpha"))}})
      (is (= 1 (count (surface/admissible-listings st now))) "only beta visible")
      (let [result (exec actor "t-pub"
                         {:op :publish-listing
                          :listing-id (:listing/id (listing-of st "merchant.alpha"))})]
        (is (= :done (:status result)))
        (is (= :commit (:disposition (:state result))))
        (is (= 2 (count (surface/admissible-listings st now)))
            "the buyer surface now shows it"))
      (is (some? gamma)))))

(deftest publishing-an-unsellable-sellers-listing-hard-holds
  (let [st (store/seed-db)
        actor (operation/build st)
        result (exec actor "t-gamma"
                     {:op :publish-listing
                      :listing-id (:listing/id (listing-of st "merchant.gamma"))})]
    (is (= :done (:status result)) "not :interrupted — no human is asked")
    (is (= :hold (:disposition (:state result))))
    (is (some #{:admission-refused} (map :rule (:violations (first (store/ledger st))))))
    (is (= 2 (count (surface/admissible-listings st now)))
        "gamma still invisible to buyers")))

(deftest restricted-category-hard-holds-through-the-compiled-graph
  (let [st (store/seed-db)
        actor (operation/build st)]
    (store/with-policy st (assoc (store/policy st) :restricted #{:beverages}))
    (let [result (exec actor "t-restricted"
                       {:op :publish-listing
                        :listing-id (:listing/id (listing-of st "merchant.alpha"))})]
      (is (= :hold (:disposition (:state result))))
      (is (some #{:restricted-category}
                (:reasons (first (:violations (first (store/ledger st))))))))))

(deftest suppression-is-the-always-available-correction-path
  (testing "publish may auto-commit precisely because taking it down is
            equally fast — assert that the correction path really is"
    (let [st (store/seed-db)
          actor (operation/build st)
          lid (:listing/id (listing-of st "merchant.alpha"))
          result (exec actor "t-suppress"
                       {:op :suppress-listing :listing-id lid
                        :patch {:reason "seller request"}})]
      (is (= :done (:status result)))
      (is (= :commit (:disposition (:state result))))
      (is (= :suppressed (:listing/status (store/listing-record st lid))))
      (is (zero? (:count (surface/search st now "冷えた炭酸飲料")))))))

(deftest listing-concern-escalates-and-threads-the-real-proposal
  (let [distinctive (str "TEST-CONCERN-" (rand-int 1000000000))
        st (store/seed-db)
        actor (operation/build st)
        held (exec actor "t-concern"
                   {:op :flag-listing-concern
                    :listing-id (:listing/id (listing-of st "merchant.alpha"))
                    :patch {:concern distinctive}})]
    (is (= :interrupted (:status held)))
    (is (empty? (store/ledger st)))
    (let [approved (g/run* actor {:approval {:status :approved :by "trust-safety-01"}}
                           {:thread-id "t-concern" :resume? true})]
      (is (= :done (:status approved)))
      (is (= distinctive (:concern (:payload (first (store/listing-log st)))))))))

(deftest drafting-against-an-unknown-offer-hard-holds
  (let [st (store/seed-db)
        actor (operation/build st)
        result (exec actor "t-draft-bad"
                     {:op :draft-listing
                      :patch {:offer "offer.nonexistent" :product product
                              :seller "merchant.alpha" :title "Ghost"
                              :category :beverages
                              :images ["https://example.test/g.jpg"]}})]
    (is (= :hold (:disposition (:state result))))
    (is (some #{:offer-unknown} (map :rule (:violations (first (store/ledger st))))))))

(deftest phase-1-disables-publishing
  (let [st (store/seed-db)
        actor (operation/build st)
        result (exec actor "t-phase1"
                     {:op :publish-listing
                      :listing-id (:listing/id (listing-of st "merchant.alpha"))}
                     (assoc op-context :phase 1))]
    (is (= :hold (:disposition (:state result))))
    (is (= :phase-disabled (:phase-reason (first (store/ledger st)))))))

(deftest phase-2-publishes-only-with-human-approval
  (testing "the same op that auto-commits at phase 3 escalates at phase 2 —
            the rollout dial genuinely works, it is not decorative"
    (let [st (store/seed-db)
          actor (operation/build st)
          held (exec actor "t-phase2"
                     {:op :publish-listing
                      :listing-id (:listing/id (listing-of st "merchant.alpha"))}
                     (assoc op-context :phase 2))]
      (is (= :interrupted (:status held)))
      (let [approved (g/run* actor {:approval {:status :approved :by "ops-01"}}
                             {:thread-id "t-phase2" :resume? true})]
        (is (= :commit (:disposition (:state approved))))))))
