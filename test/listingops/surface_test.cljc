(ns listingops.surface-test
  "The buyer-facing surface is the first one in this fleet, and the one
  place where an admission mistake becomes visible to a member of the
  public. These tests assert the rule the surface is built on: it can
  only render what admission already cleared."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [listingops.store :as store]
            [listingops.surface :as surface]))

(def now "2026-06-01T00:00:00Z")
(def product "gtin.05449000000996")

(defn- db [] (store/seed-db))

(deftest only-cleared-listings-are-visible
  (let [st (db)
        cleared (surface/admissible-listings st now)]
    (testing "alpha and beta are live and sellable; gamma is :draft AND
              not payout-bound, so it never reaches a buyer"
      (is (= #{"merchant.alpha" "merchant.beta"}
             (set (map (comp :listing/seller first) cleared)))))
    (testing "every cleared listing carries the verdict that cleared it —
              there is no path by which a renderer sees a listing without
              also seeing why it was allowed"
      (is (every? (fn [[_ adm]] (= :admissible (:admission/outcome adm))) cleared)))))

(deftest search-cannot-return-what-admission-refused
  (let [st (db)]
    (is (pos? (:count (surface/search st now "cola"))))
    (testing "gamma's listing is titled 'cheap' and is NOT findable"
      (is (zero? (:count (surface/search st now "cheap")))))
    (testing "suppressing alpha removes it from the index entirely — it is
              absent from the data structure, not filtered at render time"
      (store/commit-record! st {:op :suppress-listing
                                :value {:listing-id (->> (store/all-listing-records st)
                                                         (filter #(= "merchant.alpha" (:listing/seller %)))
                                                         first :listing/id)}})
      (let [ids (set (map :listing-id (:results (surface/search st now "cola"))))]
        (is (not-any? #(str/includes? (str %) "alpha") ids))))))

(deftest restricting-a-category-empties-the-buyer-surface
  (let [st (db)]
    (is (pos? (:count (surface/search st now "cola"))))
    (store/with-policy st (assoc (store/policy st) :restricted #{:beverages}))
    (is (zero? (:count (surface/search st now "cola")))
        "a policy change takes effect on the buyer surface immediately")
    (is (empty? (surface/sitemap st now)))))

(deftest product-page-shows-every-offer-not-just-the-winner
  (let [st (db)
        page (surface/product-page st now product)]
    (is (= product (:product page)))
    (testing "all three offers appear, including the one whose seller cannot sell"
      (is (= 3 (:offer-count page))))
    (testing "but only sellable sellers' offers can win the buy box"
      (is (= "merchant.beta" (:seller (:buy-box page)))
          "beta at 1100 beats alpha at 1200; gamma at 900 is excluded")
      (is (some #(= :seller-ineligible (:reason %)) (:excluded page))))
    (testing "only cleared listings are rendered"
      (is (= 2 (count (:listings page)))))))

(deftest buy-box-explanation-is-reproducible-from-public-facts
  (let [st (db)
        e (surface/buy-box-explanation st now product)]
    (is (= "merchant.beta" (->> (:ranked e) first :offer-id
                                (str) (re-find #"merchant\.\w+"))))
    (testing "the ranking key is published, so a seller who lost can
              reproduce the result — this function does not exist on a
              closed platform"
      (is (= ["landed price asc" "condition rank asc"
              "lead time asc (unknown last)" "offer id asc"]
             (:ranking-key e))))
    (is (seq (:excluded e)))))

(deftest visibility-diagnostic-answers-why-is-my-item-not-showing
  (let [st (db)
        rows (surface/offers-visible-to-buyers st now)
        by-seller (into {} (map (juxt :seller identity) rows))]
    (is (true? (:visible? (by-seller "merchant.alpha"))))
    (is (true? (:visible? (by-seller "merchant.beta"))))
    (testing "gamma gets an actionable reason rather than silence"
      (let [g (by-seller "merchant.gamma")]
        (is (false? (:visible? g)))
        (is (some #{:seller-not-sellable :not-yet-live} (:reason g)))))))

(deftest seller-page-lists-only-that-sellers-cleared-listings
  (let [st (db)]
    (is (= 1 (:count (surface/seller-page st now "merchant.alpha"))))
    (is (zero? (:count (surface/seller-page st now "merchant.gamma"))))))

(deftest sitemap-is-deterministic
  (let [st (db)]
    (is (= [product] (surface/sitemap st now)))
    (is (= (surface/sitemap st now) (surface/sitemap st now)))))

(deftest an-expired-credential-empties-that-sellers-surface
  (testing "the clock is the caller's — the surface has none"
    (let [st (db)
          later "2028-01-01T00:00:00Z"]
      (is (pos? (:count (surface/search st now "cola"))))
      (is (zero? (:count (surface/search st later "cola")))
          "every demo credential expires 2027-01-01"))))
