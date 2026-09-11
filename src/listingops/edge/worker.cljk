(ns listingops.edge.worker
  "The buyer surface as a Cloudflare Worker — the fleet's first
  public-facing deploy.

  ## What is and is not in this bundle

  It requires `search.model` and a published snapshot. That is all.

  It deliberately does NOT require `listingops.store`,
  `marketplace.seller`, `ekyc` or `aml`. Running admission at request
  time would drag identity-verification and sanctions-screening code
  into a public, unauthenticated endpoint to answer a question that was
  settled when the snapshot was published. A buyer surface has no
  business holding that code.

  The structural guarantee survives: the snapshot is built from
  `listingops.surface/admissible-listings`, so a refused listing is
  absent from the data this worker was given.

  Bracket access (`aget`) throughout, never `.-foo`, for
  `:advanced-optimization` safety — the same convention
  `partners.edge.intake` documents."
  (:require [listingops.edge.snapshot :as snap]
            [search.model :as search]))

(defn- json [body status]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status
                     :headers #js {"content-type" "application/json; charset=utf-8"
                                   "access-control-allow-origin" "*"
                                   "cache-control" "public, max-age=60"}}))

(defn- html [body status]
  (js/Response. body
                #js {:status status
                     :headers #js {"content-type" "text/html; charset=utf-8"
                                   "cache-control" "public, max-age=60"}}))

(def ^:private snapshot snap/data)

(defn- q-search [q]
  (let [hits (search/search (:snapshot/index snapshot) q)]
    {:query q
     :count (count hits)
     :results (mapv (fn [d] {:listing-id (:search/id d)
                             :title (:search/title d)
                             :score (:search/score d)})
                    hits)}))

(defn- provenance
  "Every response carries when the snapshot was cut. A stale surface
  should be visible to whoever is looking at it, not silent."
  []
  {:generated-at (:snapshot/generated-at snapshot)
   :as-of (:snapshot/as-of snapshot)
   :admission-applied? (:snapshot/admission-applied? snapshot)
   :source "seeded demo catalog — not live inventory"})

(defn- index-page []
  (str "<!doctype html><meta charset=utf-8>"
       "<meta name=viewport content='width=device-width,initial-scale=1'>"
       "<title>cloud-itonami marketplace — buyer surface</title>"
       "<style>"
       ":root{color-scheme:light dark;--fg:#111;--mut:#666;--line:#e5e5e5;--bg:#fff}"
       "@media(prefers-color-scheme:dark){:root{--fg:#eee;--mut:#999;--line:#333;--bg:#111}}"
       "body{font:16px/1.6 ui-sans-serif,system-ui,sans-serif;max-width:44rem;"
       "margin:0 auto;padding:2rem 1.25rem;color:var(--fg);background:var(--bg)}"
       "h1{font-size:1.4rem;margin:0 0 .25rem}"
       "p.sub{color:var(--mut);margin:0 0 2rem;font-size:.9rem}"
       "code{background:color-mix(in srgb,var(--fg) 8%,transparent);"
       "padding:.15em .4em;border-radius:4px;font-size:.85em}"
       "li{margin:.4rem 0}ul{padding-left:1.2rem}"
       ".note{border-left:3px solid var(--line);padding:.5rem 0 .5rem 1rem;"
       "color:var(--mut);font-size:.9rem;margin:1.5rem 0}"
       "</style>"
       "<h1>marketplace — buyer surface</h1>"
       "<p class=sub>" (:snapshot/listing-count snapshot) " listing(s), "
       (count (:snapshot/sitemap snapshot)) " product(s). Snapshot cut "
       (:snapshot/generated-at snapshot) ".</p>"
       "<div class=note>Seeded demo catalog, not live inventory. Every item here "
       "passed listing admission before the snapshot was cut — a refused listing "
       "is absent from the data this worker was given, not filtered out on "
       "render.</div>"
       "<h2 style='font-size:1rem'>Endpoints</h2><ul>"
       "<li><code>GET /api/search?q=cola</code></li>"
       "<li><code>GET /api/product/&lt;product-id&gt;</code></li>"
       "<li><code>GET /api/buy-box/&lt;product-id&gt;</code></li>"
       "<li><code>GET /api/sitemap</code></li>"
       "<li><code>GET /health</code></li></ul>"
       "<h2 style='font-size:1rem'>Products</h2><ul>"
       (apply str (for [p (:snapshot/sitemap snapshot)]
                    (str "<li><code>" p "</code> — <a href='/api/product/" p "'>page</a>"
                         " · <a href='/api/buy-box/" p "'>why this offer won</a></li>")))
       "</ul>"
       "<div class=note>The buy-box endpoint publishes the full ranking key and "
       "every excluded offer with its reason, so a seller who lost can reproduce "
       "the result. On a closed platform that endpoint does not exist.</div>"))

(defn- handle [request]
  (let [url (js/URL. (aget request "url"))
        path (aget url "pathname")
        params (aget url "searchParams")]
    (cond
      (= path "/health")
      (json (merge {:ok true :service "cloud-itonami-marketplace-buyer"} (provenance)) 200)

      (= path "/api/sitemap")
      (json {:products (:snapshot/sitemap snapshot) :provenance (provenance)} 200)

      (= path "/api/search")
      (let [q (or (.get params "q") "")]
        (if (empty? q)
          (json {:error "missing q"} 400)
          (json (assoc (q-search q) :provenance (provenance)) 200)))

      (.startsWith path "/api/product/")
      (let [pid (.slice path (count "/api/product/"))
            page (get (:snapshot/pages snapshot) pid)]
        (if page
          (json (assoc page :provenance (provenance)) 200)
          (json {:error "unknown product" :product pid} 404)))

      (.startsWith path "/api/buy-box/")
      (let [pid (.slice path (count "/api/buy-box/"))
            page (get (:snapshot/pages snapshot) pid)]
        (if page
          (json {:product pid
                 :winner (:buy-box page)
                 :offers (:offers page)
                 :excluded (:excluded page)
                 :ranking-key ["landed price asc" "condition rank asc"
                               "lead time asc (unknown last)" "offer id asc"]
                 :note "Every component is observable by sellers, so a seller who lost can reproduce this."
                 :provenance (provenance)}
                200)
          (json {:error "unknown product" :product pid} 404)))

      (= path "/")
      (html (index-page) 200)

      :else (json {:error "not found"} 404))))

(def app
  (clj->js {:fetch (fn [request _env _ctx]
                     (js/Promise.resolve (handle request)))}))
