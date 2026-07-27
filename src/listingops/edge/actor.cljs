(ns listingops.edge.actor
  "The listing actor's WRITE host — deliberately a different Worker from
  the buyer surface.

  `listingops.edge.worker` serves the public buyer surface and requires
  `search.model` and a published snapshot, and nothing else. That is a
  structural guarantee, verified on the built bundle: no
  `marketplace.seller`, no `ekyc`, no `aml`, no credential ever reaches
  a public unauthenticated endpoint. Bolting the write path onto that
  Worker would have destroyed the guarantee for the sake of one fewer
  deployment, so the write path is here instead.

  What this host writes into the shared `marketplace` ref:

    offers    a seller's own price and stock. No proposal, because there
              is no judgement — a seller stating their price is a fact
              about them. Whether it may be LISTED is the separate
              decision below.
    listings  drafted and published through the governor, which checks
              the restricted-category floor, the required attestations,
              the counterfeit signal, and — reading the credential
              `-marketplace-onboarding` wrote into this same ref —
              whether the seller may sell at all.

  The order actor reads the offers this host publishes. That is the
  entire integration: one ref, one join, no service call."
  (:require [marketplace.catalog :as catalog]
            [marketplace.edge :as edge]
            [marketplace.listing :as listing]
            [listingops.advisor :as advisor]
            [listingops.governor :as governor]
            [listingops.phase :as phase]
            [listingops.store :as store]))

(def ^:private ops
  {:advise      (fn [st req] (advisor/-advise (advisor/mock-advisor) st req))
   :check       governor/check
   :disposition phase/verdict->disposition
   :gate        phase/gate
   :commit!     (fn [st proposal req]
                  (store/commit-record! st {:op (:op proposal)
                                            :listing-id (:listing-id req)
                                            :value (:value proposal)
                                            :payload (:value proposal)}))
   :ledger!     store/append-ledger!
   :hold-fact   governor/hold-fact})

(defn- ctx [body]
  {:actor-id "listingops-edge"
   :phase (get body "phase" 3)
   :now (get body "now" "2026-06-01T00:00:00Z")})

(defn- kw-map [m] (into {} (map (fn [[k v]] [(keyword k) v]) m)))

;; ───────────────────────── operations ─────────────────────────

(defn- publish-offer
  "Write a seller's offer. The offer id is DERIVED (product, condition,
  seller) by `marketplace.catalog/offer`, not taken from the request —
  a caller cannot mint an id that collides with someone else's."
  [client body]
  (let [seller (get body "seller")]
    (edge/with-store
      {:client client :wants {:credential [seller]} :store-fn store/kotobase-store}
      (fn [st]
        (if-not (store/sellable? st seller (get body "now" "2026-06-01T00:00:00Z"))
          {:ref seller :disposition "hold" :violations ["seller-not-sellable"]}
          (let [o (catalog/offer {:product (get body "product")
                                  :seller seller
                                  :price-minor (get body "price-minor")
                                  :currency (get body "currency" "JPY")
                                  :quantity (get body "quantity" 0)
                                  :condition (keyword (get body "condition" "new"))})]
            (store/put-offer! st o)
            {:ref (:offer/id o) :disposition "commit" :violations []
             :offer-id (:offer/id o) :price-minor (:offer/price-minor o)}))))))

(defn- draft-listing [client body]
  (let [oid (get body "offer-id")
        seller (get body "seller")]
    (edge/with-store
      {:client client
       :wants {:credential [seller] :offer [oid] :listing :all :policy :all}
       :store-fn store/kotobase-store}
      (fn [st]
        (let [l (listing/listing
                 {:offer oid :product (get body "product") :seller seller
                  :title (get body "title") :description (get body "description")
                  :category (keyword (get body "category" "general"))
                  :keywords (vec (get body "keywords" []))
                  :images (vec (get body "images" []))
                  :status :draft
                  :attested (set (map keyword (get body "attested" [])))})]
          (edge/outcome (:listing/id l)
                        (edge/run ops st (ctx body)
                                  {:op :draft-listing :listing-id (:listing/id l)
                                   :ref (:listing/id l)
                                   :patch (assoc (kw-map body) :listing l)})))))))

(defn- publish-listing [client body]
  (let [lid (get body "listing-id")]
    (edge/with-store
      {:client client
       :wants {:listing [lid] :offer :all :credential :all :policy :all}
       :store-fn store/kotobase-store}
      (fn [st]
        (edge/outcome lid (edge/run ops st (ctx body)
                                    {:op :publish-listing :listing-id lid :ref lid
                                     :patch {:listing-id lid}}))))))

;; ───────────────────────── routes ─────────────────────────

(defn- gated [request env f]
  (if-not (edge/authorised? request env)
    (js/Promise.resolve (edge/json {:error "unauthorised"} 401))
    (-> (.json request) (.then #(f (js->clj %))) (.then #(edge/json % 200)))))

(defn- routes [client request env method path _url]
  (cond
    (and (= method "POST") (= path "/offers")) (gated request env #(publish-offer client %))
    (and (= method "POST") (= path "/listings")) (gated request env #(draft-listing client %))
    (and (= method "POST") (= path "/publish")) (gated request env #(publish-listing client %))

    ;; Offers and live listings are the catalog. Open: this is precisely
    ;; the data a buyer surface is built from.
    (and (= method "GET") (= path "/offers"))
    (-> (edge/read-all client :offer)
        (.then (fn [os]
                 (edge/json {:offers (mapv (fn [o] {:offer-id (:offer/id o)
                                                    :seller (:offer/seller o)
                                                    :product (:offer/product o)
                                                    :price-minor (:offer/price-minor o)
                                                    :currency (:offer/currency o)
                                                    :quantity (:offer/quantity o)})
                                           os)}
                            200))))

    (and (= method "GET") (= path "/listings"))
    (-> (edge/read-all client :listing)
        (.then (fn [ls]
                 (edge/json {:listings (mapv (fn [l] {:listing-id (:listing/id l)
                                                      :offer (:listing/offer l)
                                                      :seller (:listing/seller l)
                                                      :title (:listing/title l)
                                                      :status (str (:listing/status l))})
                                             ls)}
                            200))))

    ;; /escalations and /ledger, implemented once in marketplace.edge.
    ;; Every high-stakes move in this actor escalates rather than committing
    ;; on a machine's say-so; without a way to READ those, each of those gates
    ;; is a black hole.
    :else (edge/ledger-routes client request env method path :listingops)))

(def app
  (clj->js
   {:fetch (fn [request env _ctx]
             (edge/serve "cloud-itonami-marketplace-listing-actor" request env routes))}))
