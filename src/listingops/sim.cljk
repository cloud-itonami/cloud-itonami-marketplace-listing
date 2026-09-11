(ns listingops.sim
  "Offline demo: publish a listing, watch it appear on the buyer surface,
  restrict its category and watch it disappear. `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [listingops.operation :as operation]
            [listingops.store :as store]
            [listingops.surface :as surface]))

(def ^:private now "2026-06-01T00:00:00Z")
(def ^:private ctx {:actor-id "listing-demo" :phase 3 :now now})
(def ^:private product "gtin.05449000000996")

(defn- run-req! [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- listing-of [st seller]
  (->> (store/all-listing-records st)
       (filter #(= seller (:listing/seller %))) first))

(defn -main [& _]
  (let [s (store/seed-db)
        actor (operation/build s)]

    (println "\n=== 1. 買い手から見える出品 ===")
    (doseq [[l _] (surface/admissible-listings s now)]
      (println "  " (:listing/seller l) "/" (:listing/title l)))

    (println "\n=== 2. 商品ページ（負けたオファーも全部見せる）===")
    (let [p (surface/product-page s now product)]
      (println "  出品者数:" (:seller-count p) " オファー数:" (:offer-count p))
      (println "  buy box :" (:seller (:buy-box p)) (:price-minor (:buy-box p)) (:currency (:buy-box p)))
      (doseq [o (:offers p)]
        (println "    -" (:seller o) (:price-minor o) (:currency o)))
      (println "  除外    :" (:excluded p)))

    (println "\n=== 3. buy box の説明（負けた出品者が再現できる）===")
    (let [e (surface/buy-box-explanation s now product)]
      (println "  勝者      :" (:winner e))
      (println "  ranking-key:" (:ranking-key e)))

    (println "\n=== 4. 出品できない出品者（merchant.gamma）の公開 → HARD hold ===")
    (let [r (run-req! actor "sim-gamma"
                      {:op :publish-listing :listing-id (:listing/id (listing-of s "merchant.gamma"))})]
      (println "  status     :" (:status r))
      (println "  disposition:" (:disposition (:state r)))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 5. なぜ自分の商品が出ないのか（出品者向け診断）===")
    (doseq [row (surface/offers-visible-to-buyers s now)]
      (println "  " (:seller row) "visible:" (:visible? row) "reason:" (:reason row)))

    (println "\n=== 6. カテゴリを規制 → 買い手面から即座に消える ===")
    (println "  規制前の検索 'cola':" (:count (surface/search s now "cola")) "件")
    (store/with-policy s (assoc (store/policy s) :restricted #{:beverages}))
    (println "  規制後の検索 'cola':" (:count (surface/search s now "cola")) "件")
    (println "  sitemap:" (surface/sitemap s now))))
