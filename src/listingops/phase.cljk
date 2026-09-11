(ns listingops.phase
  "Phase 0->3 staged rollout for the marketplace listing actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-draft     -- listing copy may be drafted, every
                                   write needs human approval.
    Phase 2  assisted-publish   -- adds publish and suppress, still
                                   approval-gated.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                   drafting, publishing, suppressing and
                                   reindexing may auto-commit.

  ## Why publishing is allowed in the auto set here

  This is the one actor in the marketplace stack whose highest-value op
  MAY run unattended, and the difference is worth stating rather than
  leaving as an inconsistency:

    - Issuing a seller credential (onboarding) is irreversible in
      practice and admits a party to trade. Permanently human-gated.
    - Merging two canonical identities (gtin-catalog) accumulates
      aliases against the survivor and is expensive to unwind.
      Permanently human-gated.
    - Publishing a listing is REVERSIBLE. `:suppress-listing` is always
      available, always cheap, and itself auto-eligible, so the
      correction path is as fast as the mistake.

  The safety does not come from this phase table; it comes from the
  governor having already re-derived admission from the operator's
  stored policy and the seller's credential, and from the seller having
  been human-approved upstream. A publish whose admission came back
  `:review` is marked high-stakes by the governor and escalates even at
  phase 3.

  `:flag-listing-concern` is deliberately ABSENT from every phase's
  `:auto` set, INCLUDING phase 3 -- surfacing a concern is the step
  before a human looks, never the resolution of one.
  `listingops.governor`'s own `always-escalate-ops` enforces the same
  invariant independently."
  (:require [listingops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:flag-listing-concern` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                  :auto #{}}
   1 {:label "assisted-draft"   :writes #{:draft-listing}     :auto #{}}
   2 {:label "assisted-publish" :writes #{:draft-listing :publish-listing
                                          :suppress-listing}  :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:draft-listing :publish-listing :suppress-listing :reindex-catalog}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a ListingGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
