(ns listingops.surface
  "The buyer-facing surface -- the first one in this fleet.

  Every other `cloud-itonami` actor faces an OPERATOR. This one has to
  answer a member of the public: 'what can I buy, from whom, at what
  price?' That makes it the one place where a mistake in the admission
  layer becomes visible to someone who never agreed to anything.

  So the surface is built on one rule: **it can only render what
  admission already cleared.** It never takes a listing and decides for
  itself whether to show it; it consumes the decisions
  `listingops.store/admission-for` already made and drops everything
  else before an index or a page is even constructed. A listing the
  governor refused is not filtered out at render time -- it is absent
  from the data structure the renderer walks.

  Pure data in, pure data out: these functions return plain maps a
  transport (a Cloudflare Worker, a static generator, an XRPC endpoint)
  serialises. No HTTP, no clock -- `now` is supplied by the caller, the
  same discipline the rest of this stack follows."
  (:require [listingops.store :as store]
            [marketplace.catalog :as catalog]
            [marketplace.listing :as listing]))

(defn admissible-listings
  "Every stored listing whose admission is `:admissible` at `now`, paired
  with the verdict that cleared it.

  Returning the verdict alongside the listing is deliberate: the index
  and page builders below take these PAIRS, so there is no path by which
  they see a listing without also seeing why it was allowed."
  [st now]
  (->> (store/all-listing-records st)
       (map (fn [l] [l (store/admission-for st l now)]))
       (filter (fn [[_ adm]] (= :admissible (:admission/outcome adm))))
       vec))

(defn search-index
  "Build the buyer-facing search index from cleared listings only.

  Delegates to `marketplace.listing/index-admissible`, which takes the
  pre-decided verdicts -- the actor does not run admission twice and so
  cannot disagree with itself between the two runs."
  [st now]
  (listing/index-admissible (admissible-listings st now)))

(defn search
  "Run a buyer query. Returns `{:query .. :results [..] :count ..}`.

  Ranking is entirely `search.model`'s -- this namespace adds no boost,
  no paid placement and no reordering, the same commitment
  `marketplace.catalog/buy-box` makes for the buy box."
  [st now q]
  (let [hits (listing/search-listings (search-index st now) q)]
    {:query   q
     :count   (count hits)
     :results (mapv (fn [d] {:listing-id (:search/id d)
                             :title      (:search/title d)
                             :score      (:search/score d)})
                    hits)}))

(defn- eligible-offer-pred
  "Only offers whose seller is genuinely sellable may take the buy box.
  Passed into `catalog/buy-box` so an unadmitted seller's cheap offer
  cannot win it -- price alone is never enough."
  [st now]
  (fn [o] (store/sellable? st (:offer/seller o) now)))

(defn product-page
  "Everything a buyer-facing product page needs for one canonical
  product.

  Shows the losing offers as well as the winner. A marketplace that
  displays only the buy-box winner is hiding the mechanism, which is
  exactly what this design set out not to do (ADR-2607264000 D1) -- and
  `marketplace.listing/product-page` additionally reports orphaned
  listings and unlisted offers rather than silently dropping them, so a
  wiring mistake shows up here instead of as a missing item a buyer
  never knows to ask about."
  [st now product-id]
  (let [cleared (admissible-listings st now)
        page (listing/product-page (store/catalog-index st)
                                   product-id
                                   (mapv first cleared)
                                   {:eligible? (eligible-offer-pred st now)})]
    {:product        product-id
     :seller-count   (count (:page/sellers page))
     :offer-count    (:page/offer-count page)
     :buy-box        (let [w (:buy-box/winner (:page/buy-box page))]
                       (when w
                         {:offer-id (:offer/id w)
                          :seller   (:offer/seller w)
                          :price-minor (:offer/price-minor w)
                          :currency (:offer/currency w)}))
     :offers         (mapv (fn [o] {:offer-id (:offer/id o)
                                    :seller (:offer/seller o)
                                    :price-minor (:offer/price-minor o)
                                    :currency (:offer/currency o)
                                    :condition (:offer/condition o)
                                    :availability (:offer/availability o)})
                           (:page/offers page))
     :excluded       (:buy-box/excluded (:page/buy-box page))
     :listings       (mapv (fn [l] {:listing-id (:listing/id l)
                                    :title (:listing/title l)
                                    :seller (:listing/seller l)})
                           (:page/listings page))
     ;; Surfaced rather than hidden -- see the docstring.
     :orphan-listings (mapv :listing/id (:page/orphan-listings page))
     :unlisted-offers (mapv :offer/id (:page/unlisted-offers page))}))

(defn seller-page
  "A seller's public storefront: every cleared listing they hold."
  [st now seller-id]
  (let [ls (->> (admissible-listings st now)
                (map first)
                (filter #(= seller-id (:listing/seller %))))]
    {:seller   seller-id
     :count    (count ls)
     :listings (mapv (fn [l] {:listing-id (:listing/id l)
                              :title (:listing/title l)
                              :product (:listing/product l)})
                     ls)}))

(defn sitemap
  "Canonical products that have at least one cleared listing -- what a
  crawler or a static generator should walk. Sorted for determinism, so
  regenerating the surface produces a stable diff."
  [st now]
  (->> (admissible-listings st now)
       (map (comp :listing/product first))
       distinct
       sort
       vec))

(defn offers-visible-to-buyers
  "Diagnostic: which catalog offers are actually reachable by a buyer,
  and which are not, with the reason.

  An operator needs this because 'my item is not showing up' is the
  single most common seller complaint, and answering it by guessing is
  how a marketplace loses sellers' trust."
  [st now]
  (let [cleared-offer-ids (set (map (comp :listing/offer first)
                                    (admissible-listings st now)))]
    (mapv (fn [o]
            (let [l (first (filter #(= (:offer/id o) (:listing/offer %))
                                   (store/all-listing-records st)))]
              {:offer-id (:offer/id o)
               :seller (:offer/seller o)
               :visible? (contains? cleared-offer-ids (:offer/id o))
               :reason (cond
                         (contains? cleared-offer-ids (:offer/id o)) nil
                         (nil? l) :no-listing
                         :else (:admission/reasons (store/admission-for st l now)))}))
          (store/all-offer-records st))))

(defn buy-box-explanation
  "Why this offer won, and why the others did not -- reproducible by any
  seller from public facts.

  On a closed platform this function does not exist; the ranking is the
  platform's most opaque lever. Exposing it is a deliberate design
  commitment, not a debugging aid."
  [st now product-id]
  (let [bb (catalog/buy-box (store/catalog-index st) product-id
                            {:eligible? (eligible-offer-pred st now)})]
    {:product   product-id
     :winner    (:offer/id (:buy-box/winner bb))
     :ranked    (mapv (fn [o] {:offer-id (:offer/id o)
                               :price-minor (:offer/price-minor o)
                               :condition (:offer/condition o)
                               :lead-time-days (:offer/lead-time-days o)})
                      (:buy-box/ranked bb))
     :excluded  (:buy-box/excluded bb)
     :currency  (:buy-box/currency bb)
     :ranking-key ["landed price asc" "condition rank asc"
                   "lead time asc (unknown last)" "offer id asc"]}))
