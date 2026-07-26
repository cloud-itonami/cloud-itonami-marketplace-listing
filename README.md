# cloud-itonami-marketplace-listing

Open Business Blueprint (implemented actor): **what may be shown to
buyers, and how a buyer finds it.**

This repository publishes a listing-admission and discovery actor —
drafting listing copy, publishing, suppressing, reindexing, and flagging
listing concerns — plus **the first buyer-facing surface in this fleet**.
Every other `cloud-itonami` actor faces an operator; this one has to
answer a member of the public: *what can I buy, from whom, at what
price?*

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime — here it is **ListingAdvisor ⊣ ListingGovernor**. Admission
rules, the search projection and the buy box come from
[`kotoba-lang/marketplace`](https://github.com/kotoba-lang/marketplace).
Design record:
[ADR-2607264000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607264000-marketplace-federated-commerce-layer.edn).

> **Why an actor layer at all?** Writing product copy is exactly what an
> LLM is good at. Deciding whether goods may lawfully be sold is exactly
> what it must never do. The advisor drafts titles and descriptions
> freely and states category and authenticity only as the *seller's*
> claim; the governor re-derives admission from the operator's stored
> policy and the seller's own credential, so nothing the advisor asserts
> about eligibility carries any weight.

## Refusal to display is not a finding of wrongdoing

Every hard check answers *may this be shown to buyers?* — none answers
*did this seller break the law?* A restricted category or a counterfeit
signal makes a listing undisplayable; it does not make the seller a
counterfeiter, and this actor never records that it did. Any claim to
have determined legality, authenticity or seller wrongdoing is a HARD,
permanent scope exclusion — the same discipline
[ISIC 4791](https://github.com/cloud-itonami/cloud-itonami-isic-4791)
established for fraud determinations.

## Policy is the operator's, not the library's

There is no honest way for code to enumerate what every jurisdiction
prohibits. `marketplace.listing/restricted-baseline` is a **floor** of
categories essentially every consumer marketplace restricts; the real
list is `:restricted` in this store's policy, supplied per deployment. A
governor that read its policy from a constant could not be audited
against what the operator actually configured.

## Publish may auto-commit. Admission never could.

This is the one actor in the marketplace stack whose highest-value op
may run unattended, and the asymmetry is deliberate:

| Act | Reversible? | Gate |
|---|---|---|
| Issuing a seller credential (`-onboarding`) | no — admits a party to trade | **permanently human** |
| Merging canonical identities (`gtin-catalog`) | no — aliases accumulate against the survivor | **permanently human** |
| Publishing a listing | **yes** — `:suppress-listing` is always available, always cheap, and itself auto-eligible | governor + phase |

The safety does not come from the phase table. It comes from the seller
having been human-approved upstream and from the governor re-deriving
admission before every publish. A publish whose admission returns
`:review` (e.g. a missing attestation) escalates even at phase 3 —
`marketplace.listing`'s three-valued outcome is preserved rather than
flattened, because `:refused` means *never* and `:review` means *a human
may decide*.

## The buyer surface can only render what admission cleared

`listingops.surface` never takes a listing and decides for itself
whether to show it. It consumes verdicts already made and drops
everything else **before** an index or a page is constructed — a refused
listing is absent from the data structure the renderer walks, not
filtered at render time.

```clojure
(surface/search st now "cola")            ; only cleared listings are even indexed
(surface/product-page st now product-id)  ; every offer, not just the winner
(surface/buy-box-explanation st now pid)  ; why this offer won, reproducibly
(surface/offers-visible-to-buyers st now) ; "why isn't my item showing?" — with reasons
```

`buy-box-explanation` is a deliberate design commitment, not a debugging
aid. On a closed platform the ranking function is the most opaque lever
there is; here the key is published (`landed price asc`, `condition rank
asc`, `lead time asc`, `offer id asc`) and every component is observable
by sellers, so one who lost can reproduce the result exactly. Ranking is
entirely `search.model`'s and `catalog/buy-box`'s — this actor adds no
boost and no paid placement.

`offers-visible-to-buyers` exists because *"my item isn't showing up"* is
the single most common seller complaint, and answering it by guessing is
how a marketplace loses sellers' trust.

## Four HARD checks (permanent, un-overridable)

| Check | What it catches |
|---|---|
| **Offer unknown** | a listing attached to an offer that is not in the catalog |
| **Admission refused** | restricted category, counterfeit signal, unsellable seller, missing required content — one check, re-derived from stored policy and the seller's credential |
| **Effect not `:propose`** | a proposal claiming to directly actuate outside governance |
| **Scope exclusion** | any claim to have determined legality/authenticity/wrongdoing; any op outside the closed allowlist |

Seller eligibility is delegated wholly to
`marketplace.seller/sellable?`, which requires both a cleanly admissible
credential *and* a bound payout destination. This actor never
second-guesses that answer — duplicating the logic would let the two
drift apart.

```bash
clojure -M:dev:run   # publish, see it on the buyer surface, restrict the category, watch it vanish
clojure -M:test      # 32 tests, 90 assertions
clojure -M:lint
```

## Rollout phases

| Phase | Writes | Auto-commits |
|---|---|---|
| 0 read-only | — | — |
| 1 assisted-draft | `:draft-listing` | — |
| 2 assisted-publish | + `:publish-listing` `:suppress-listing` | — |
| 3 supervised-auto | all | all except `:flag-listing-concern` |

`:flag-listing-concern` never appears in the right-hand column — it is
the step *before* a human looks, never the resolution of one.
