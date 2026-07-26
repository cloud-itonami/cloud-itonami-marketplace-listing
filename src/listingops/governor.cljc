(ns listingops.governor
  "ListingGovernor -- the independent compliance layer that earns the
  ListingAdvisor the right to publish.

  The advisor has no notion of whether the offer it is writing copy for
  exists, whether that offer's seller is actually admitted to trade,
  whether the category falls inside this operator's restricted list, or
  whether an independent verification service has flagged the item. So
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  ## Refusal to display is not a finding of wrongdoing

  Every hard check here answers 'may this be shown to buyers?' and NONE
  of them answers 'did this seller break the law?'. A restricted
  category or a counterfeit signal makes the listing undisplayable; it
  does not make the seller a counterfeiter, and this actor never records
  that it did. `scope-exclusion-violations` treats any claim to have
  determined legality or authenticity as a HARD, permanent block -- the
  same discipline ISIC 4791 established for fraud determinations.

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Offer unknown       -- a listing must attach to an offer that
                              actually exists in the catalog. Never
                              trusts the proposal's own offer id without
                              a lookup.
    2. Admission refused   -- for `:publish-listing`, the listing's
                              `marketplace.listing/admission` under THIS
                              operator's stored policy must not be
                              `:refused`. Re-derived from the store's
                              policy and the seller's own credential,
                              never from the proposal's claim. This one
                              check subsumes restricted category,
                              counterfeit signal, an unsellable seller,
                              and missing required content.
    3. Effect not :propose -- any other value is a claim to directly
                              actuate outside governance.
    4. Scope exclusion     -- any claim to have determined legality,
                              authenticity or seller wrongdoing, plus
                              any op outside the closed allowlist.

  Two ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - `:flag-listing-concern` ALWAYS escalates. A `:publish-listing`
      whose admission came back `:review` (e.g. a missing attestation)
      also escalates rather than committing -- the distinction between
      `:refused` and `:review` in `marketplace.listing` is exactly the
      distinction between 'never' and 'a human may decide', and this
      governor preserves it instead of flattening both to a block.

  ## Why publishing may auto-commit at all

  Unlike the onboarding actor -- where issuing an identity permanently
  requires a human -- a clean listing on an already-admitted seller may
  publish automatically. That is the self-serve affordance a marketplace
  needs to be usable at all. The safety comes from the fact that the
  seller was already human-approved upstream, and that taking a listing
  down (`:suppress-listing`) is always available and always cheap. The
  asymmetry is deliberate: publish is reversible, admission is not."
  (:require [clojure.string :as str]
            [listingops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist."
  #{:draft-listing :publish-listing :suppress-listing :reindex-catalog
    :flag-listing-concern})

(def always-escalate-ops
  #{:flag-listing-concern})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming an
  authority this actor lacks.

  CRITICAL: every term is phrased as the DETERMINATION ('determined the
  goods are counterfeit'), never a bare noun like 'counterfeit' or
  'restricted' -- a bare noun would match inside this actor's own
  legitimate suppression proposals (whose whole job is to talk about
  counterfeit signals and restricted categories) and self-block the
  happy path. See
  `listingops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["determined the goods are counterfeit" "determined the item is counterfeit"
   "confirmed the goods are counterfeit" "concluded the goods are counterfeit"
   "ruled the listing unlawful" "determined the listing is unlawful"
   "determined the sale is illegal" "concluded the sale is illegal"
   "found the seller in violation" "found the seller liable"
   "determined the seller breached" "adjudicated the listing"
   "偽造品と断定した" "偽造品と確定した" "違法と断定した" "違法と確定した"
   "出品者の違反を認定した" "販売禁止と裁定した"])

;; ----------------------------- checks -----------------------------

(defn- listing-for
  "The listing a proposal is about: either the draft it carries, or the
  stored record it names."
  [proposal st]
  (or (get-in proposal [:value :listing])
      (some->> (get-in proposal [:value :listing-id]) (store/listing-record st))))

(defn- offer-unknown-violations
  "A listing must attach to an offer that actually exists in the catalog.

  The offer id is reached differently per op, which is why this is a
  `case` rather than one lookup: a `:draft-listing` proposal carries the
  draft (and therefore the offer id) inline, whereas a
  `:publish-listing` proposal names only a listing id and the offer must
  be reached THROUGH the stored listing.

  For `:publish-listing` this is skipped when the listing itself is
  unknown -- `admission-refused-violations` already reports
  `:listing-unknown`, and emitting both would bury the real cause behind
  a derived one."
  [proposal st]
  (case (:op proposal)
    :draft-listing
    (let [offer-id (or (get-in proposal [:value :offer-id])
                       (get-in proposal [:value :listing :listing/offer]))]
      (when-not (and offer-id (store/offer-record st offer-id))
        [{:rule :offer-unknown
          :detail (str (or offer-id "(offer-id missing)")
                       " はカタログに存在しないオファー -- 出品を作成できない")}]))

    :publish-listing
    (when-let [l (listing-for proposal st)]
      (let [offer-id (:listing/offer l)]
        (when-not (and offer-id (store/offer-record st offer-id))
          [{:rule :offer-unknown
            :detail (str (or offer-id "(offer-id missing)")
                         " は既に存在しないオファー -- 公開できない")}])))

    nil))

(defn- admission-refused-violations
  "For `:publish-listing` ONLY: the listing must not be `:refused` under
  THIS operator's stored policy.

  Re-derived from the store's policy and the seller's own credential --
  never from any admission verdict the proposal carried. An advisor
  cannot publish a restricted item by asserting it is fine."
  [proposal st now]
  (when (= :publish-listing (:op proposal))
    (if-let [l (listing-for proposal st)]
      ;; Evaluate the listing AS IT WOULD BE once live, so a :draft status
      ;; does not itself count as a reason to refuse publishing it.
      (let [adm (store/admission-for st (assoc l :listing/status :live) now)]
        (when (= :refused (:admission/outcome adm))
          [{:rule :admission-refused
            :detail (str "公開審査で拒否: " (pr-str (:admission/reasons adm)))
            :reasons (:admission/reasons adm)}]))
      [{:rule :listing-unknown
        :detail "公開対象の出品が特定できない"}])))

(defn- effect-not-propose-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "偽造品の断定・違法性の判断・出品者の違反認定など確定行為に触れる提案は永久に禁止"}])))

(defn- admission-review?
  "A `:publish-listing` whose admission is `:review` -- publishable, but
  only after a human looks. Preserves `marketplace.listing`'s three-valued
  outcome instead of flattening :review into a block."
  [proposal st now]
  (and (= :publish-listing (:op proposal))
       (when-let [l (listing-for proposal st)]
         (= :review (:admission/outcome
                     (store/admission-for st (assoc l :listing/status :live) now))))))

(defn check
  "Censors a ListingAdvisor proposal. `context` supplies `:now` (ISO-8601
  UTC) -- this governor has no clock of its own, the same discipline
  `marketplace.seller` follows.

  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
           :high-stakes? bool :hard? bool}."
  [_request context proposal store]
  (let [now (:now context)
        hard (into []
                   (concat (offer-unknown-violations proposal store)
                           (admission-refused-violations proposal store now)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        hard? (boolean (seq hard))
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                             (and (not hard?) (admission-review? proposal store now))))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :listing-id (:listing-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
