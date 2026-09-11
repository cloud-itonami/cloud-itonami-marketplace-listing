(ns listingops.store
  "SSoT for the marketplace listing actor -- what may be publicly
  displayed, and what a buyer can find.

  Three directories, all keyed by STRING ids (never keywords):

    sellers   seller credentials, as issued by
              `cloud-itonami-marketplace-onboarding`. This actor treats
              them as GROUND TRUTH it reads, never as something it can
              change -- admission to sell is that actor's business.
    offers    `marketplace.catalog` offers, keyed by offer id.
    listings  `marketplace.listing` listings, keyed by listing id.

  The store also carries the operator's own POLICY -- the restricted
  categories for this jurisdiction, the attestations this operator
  requires, and a set of offer ids an independent verification service
  has flagged. Policy lives in the store rather than in code because it
  is an operator decision that differs per deployment, and because a
  governor that read its policy from a constant could not be audited
  against what the operator actually configured.

  The ledger stays append-only."
  (:require [marketplace.catalog :as catalog]
            [marketplace.listing :as listing]
            [marketplace.persist :as persist]
            [marketplace.seller :as seller]))

(defprotocol Store
  (seller-credential [s seller-id] "Issued seller credential, or nil.")
  (all-seller-credentials [s])
  (offer-record [s offer-id] "Catalog offer, or nil.")
  (all-offer-records [s])
  (listing-record [s listing-id] "Listing, or nil.")
  (all-listing-records [s])
  (catalog-index [s] "The marketplace.catalog index built from all offers.")
  (policy [s] "{:restricted #{..} :require #{..} :counterfeit-flagged #{offer-id ..}}")
  (ledger [s])
  (listing-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (durable? [s] "False for the test-only memory backend.")
  (with-policy [s p]))

;; ----------------------------- demo data -----------------------------

(defn- cred [id kind country payout?]
  (seller/credential
   {:id id :kind kind :legal-name (str "Demo " id) :country country
    :issuer "did:web:marketplace.example"
    :issued-at "2026-01-01T00:00:00Z" :expires-at "2027-01-01T00:00:00Z"
    :status :issued :payout-bound? payout?
    :evidence {:evidence/verified-checks (if (= :company kind)
                                           #{:document-authenticity :sanctions}
                                           #{:document-authenticity :liveness :sanctions})
               :evidence/aml-status :clear
               :evidence/ekyc-complete? true}}))

(def product "gtin.05449000000996")

(defn demo-data
  "Self-contained fixtures covering the happy path and each hard check.

    merchant.alpha  fully sellable
    merchant.beta   fully sellable (a second seller on the same product,
                    which is the whole point of a marketplace)
    merchant.gamma  identity-verified but NOT payout-bound -- may not
                    have a live listing"
  []
  (let [alpha (cred "merchant.alpha" :company "JPN" true)
        beta  (cred "merchant.beta"  :company "JPN" true)
        gamma (cred "merchant.gamma" :individual "JPN" false)
        o-alpha (catalog/offer {:product product :seller "merchant.alpha"
                                :price-minor 1200 :currency "JPY" :quantity 40})
        o-beta  (catalog/offer {:product product :seller "merchant.beta"
                                :price-minor 1100 :currency "JPY" :quantity 5})
        o-gamma (catalog/offer {:product product :seller "merchant.gamma"
                                :price-minor 900 :currency "JPY" :quantity 3})]
    {:sellers {"merchant.alpha" alpha "merchant.beta" beta "merchant.gamma" gamma}
     :offers  (into {} (map (juxt :offer/id identity) [o-alpha o-beta o-gamma]))
     :listings
     (into {} (map (juxt :listing/id identity)
                   [(listing/listing {:offer (:offer/id o-alpha) :product product
                                      :seller "merchant.alpha"
                                      :title "Coca-Cola 330ml Can"
                                      :description "冷えた炭酸飲料"
                                      :category :beverages
                                      :keywords ["cola" "コーラ" "炭酸"]
                                      :images ["https://example.test/a.jpg"]
                                      :status :live
                                      :attested #{:authentic-goods :right-to-sell}})
                    (listing/listing {:offer (:offer/id o-beta) :product product
                                      :seller "merchant.beta"
                                      :title "Coca-Cola 330ml Can (bulk)"
                                      :category :beverages
                                      :keywords ["cola" "コーラ"]
                                      :images ["https://example.test/b.jpg"]
                                      :status :live
                                      :attested #{:authentic-goods :right-to-sell}})
                    (listing/listing {:offer (:offer/id o-gamma) :product product
                                      :seller "merchant.gamma"
                                      :title "Coca-Cola 330ml Can (cheap)"
                                      :category :beverages
                                      :images ["https://example.test/c.jpg"]
                                      :status :draft})]))
     :policy {:restricted #{} :require #{:authentic-goods :right-to-sell}
              :counterfeit-flagged #{}}}))

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (seller-credential [_ id] (get-in @a [:sellers id]))
  (all-seller-credentials [_] (sort-by :seller/id (vals (:sellers @a))))
  (offer-record [_ id] (get-in @a [:offers id]))
  (all-offer-records [_] (sort-by :offer/id (vals (:offers @a))))
  (listing-record [_ id] (get-in @a [:listings id]))
  (all-listing-records [_] (sort-by :listing/id (vals (:listings @a))))
  (catalog-index [s]
    (reduce catalog/add-offer (catalog/empty-catalog) (all-offer-records s)))
  (policy [_] (:policy @a))
  (durable? [_] false)
  (ledger [_] (:ledger @a))
  (listing-log [_] (:listing-log @a))
  (commit-record! [_ record]
    (swap! a update :listing-log conj record)
    (let [{:keys [op value]} record]
      (case op
        :draft-listing
        (when-let [l (:listing value)]
          (swap! a assoc-in [:listings (:listing/id l)] l))

        :publish-listing
        (swap! a update-in [:listings (:listing-id value)]
               (fn [l] (when l (assoc l :listing/status :live))))

        :suppress-listing
        (swap! a update-in [:listings (:listing-id value)]
               (fn [l] (when l (assoc l :listing/status :suppressed))))

        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-policy [s p] (swap! a assoc :policy p) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger [] :listing-log []))))

(defn mem-store [m]
  (->MemStore (atom (merge {:sellers {} :offers {} :listings {}
                            :policy {:restricted #{} :require #{} :counterfeit-flagged #{}}
                            :ledger [] :listing-log []}
                           m))))

;; ----------------------------- derived views -----------------------------

(defn sellable?
  "Is this seller allowed to have a live listing at `now`?

  Delegates wholly to `marketplace.seller/sellable?`, which requires BOTH
  a cleanly admissible credential AND a bound payout destination. This
  actor never second-guesses that answer -- admission to sell belongs to
  the onboarding actor, and duplicating its logic here would let the two
  drift apart."
  [s seller-id now]
  (boolean
   (when-let [c (seller-credential s seller-id)]
     (seller/sellable? c now (:seller/issuer c)))))

(defn admission-for
  "Run `marketplace.listing/admission` for a listing using THIS store's
  policy and seller ground truth."
  [s l now]
  (let [p (policy s)]
    (listing/admission l {:policy (:restricted p)
                          :require (:require p)
                          :seller-ok? (sellable? s (:listing/seller l) now)
                          :counterfeit-signal (contains? (:counterfeit-flagged p)
                                                         (:listing/offer l))})))

;; ----------------------------- durable store -----------------------------

(def default-policy
  "The floor a deployment starts from, not legal advice.

  Kept as a value rather than written into the ref at construction: an
  operator's policy is an operator decision, and a store that invented
  one on first boot would be deciding it for them."
  {:restricted #{} :require #{:authentic-goods :right-to-sell}
   :counterfeit-flagged #{}})

(defrecord KotobaseStore [st seed]
  Store
  ;; Seller credentials are READ here and written by
  ;; -marketplace-onboarding into the same ref. This actor never issues
  ;; one; admission to sell is that actor's decision.
  (seller-credential [_ id] (persist/get-doc (persist/ctx st :credential :seller/id) id))
  (all-seller-credentials [_] (persist/all-docs (persist/ctx st :credential :seller/id)))
  (offer-record [_ id] (persist/get-doc (persist/ctx st :offer :offer/id) id))
  (all-offer-records [_] (persist/all-docs (persist/ctx st :offer :offer/id)))
  (listing-record [_ id] (persist/get-doc (persist/ctx st :listing :listing/id) id))
  (all-listing-records [_] (persist/all-docs (persist/ctx st :listing :listing/id)))
  (catalog-index [s]
    (reduce catalog/add-offer (catalog/empty-catalog) (all-offer-records s)))
  (policy [_] (or (persist/get-doc (persist/ctx st :policy :policy/id) "listing")
                  default-policy))
  (durable? [_] (not (:persist/memory? st)))
  (ledger [_] (persist/read-events (persist/stream-ctx st :ledger)))
  (listing-log [_] (persist/read-events (persist/stream-ctx st :listing-log)))
  (commit-record! [this record]
    (persist/append-event! (persist/stream-ctx st :listing-log) seed record)
    (let [{:keys [op value]} record
          lctx (persist/ctx st :listing :listing/id)
          status! (fn [id s]
                    (when-let [l (listing-record this id)]
                      (persist/put-doc! lctx (assoc l :listing/status s))))]
      (case op
        :draft-listing   (when-let [l (:listing value)] (persist/put-doc! lctx l))
        :publish-listing (status! (:listing-id value) :live)
        :suppress-listing (status! (:listing-id value) :suppressed)
        nil))
    record)
  (append-ledger! [_ fact]
    (persist/append-event! (persist/stream-ctx st :ledger) seed fact))
  (with-policy [this p]
    (persist/put-doc! (persist/ctx st :policy :policy/id) (assoc p :policy/id "listing"))
    this))

(defn kotobase-store
  "A durable store over a HOST-INJECTED database API. Throws when the
  host has not wired one, per
  `:policy/fail-closed-without-host-injection`."
  [{:keys [db-api seq-fn]}]
  (->KotobaseStore (persist/store {:db-api db-api :actor "listingops"})
                   (or seq-fn (let [n (atom 0)] #(swap! n inc)))))

(defn put-offer!
  "Publish a catalog offer into the shared ref.

  Offers are this actor's to write and every other actor's to read — the
  order actor prices from them, settlement splits from them. There is no
  proposal here because there is no judgement: a seller stating their
  own price is a fact about them, and whether that offer may be LISTED
  is the separate decision the governor makes."
  [s offer]
  (persist/put-doc! (persist/ctx (:st s) :offer :offer/id) offer)
  offer)
