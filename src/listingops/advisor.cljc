(ns listingops.advisor
  "ListingAdvisor -- the *contained intelligence node* for the
  marketplace listing actor.

  It drafts exactly five kinds of proposal from a closed allowlist:
  drafting listing copy for an offer, publishing a listing, suppressing
  one, rebuilding the buyer search index, and flagging a listing concern
  for human triage.

  CRITICAL: it is a smart-but-untrusted advisor. Every proposal's
  `:effect` is always `:propose`; every output is censored downstream by
  `listingops.governor` before anything becomes publicly visible.

  Writing product copy is exactly the task an LLM is good at, and
  deciding whether goods may lawfully be sold is exactly the task it must
  never do. This advisor therefore drafts TITLES and DESCRIPTIONS
  freely, and states category/authenticity only as the seller's own
  claim -- the governor re-derives admission from the operator's stored
  policy and the seller's credential, so nothing the advisor asserts
  about eligibility carries any weight.

  Like every sibling actor's advisor this is a deterministic mock so the
  actor graph runs offline. In production this calls a real LLM with the
  same proposal shape."
  (:require [marketplace.listing :as listing]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn- propose-draft
  [_db {:keys [patch]}]
  (let [l (listing/listing (merge {:status :draft} patch))]
    {:op         :draft-listing
     :listing-id (:listing/id l)
     :summary    (str "オファー " (:listing/offer l) " の出品原稿を作成: "
                      (pr-str (:listing/title l)))
     :rationale  "商品説明文の草案作成のみ。出品可否・カテゴリ規制の判断は行わない。"
     :cites      (vec (keep identity [(:listing/offer l) (:listing/product l)]))
     :effect     :propose
     :value      {:offer-id (:listing/offer l) :listing l}
     :confidence 0.9}))

(defn- propose-publish
  [_db {:keys [listing-id patch]}]
  {:op         :publish-listing
   :listing-id listing-id
   :summary    (str listing-id " の公開を提案")
   :rationale  "出品の公開提案のみ。カテゴリ規制・真贋・出品者資格の判定は行わず、公開審査に委ねる。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence (or (:confidence patch) 0.88)})

(defn- propose-suppress
  "Taking a listing DOWN is the always-available, always-cheap direction.
  The rationale names the triggering signal as an observation, never as
  a determination, so it never trips `scope-excluded-terms`."
  [_db {:keys [listing-id patch]}]
  {:op         :suppress-listing
   :listing-id listing-id
   :summary    (str listing-id " の一時非表示を提案: " (pr-str (:reason patch "unknown")))
   :rationale  "観察されたシグナル（規制カテゴリ該当の疑い・真贋懸念の通報など）に基づく非表示提案のみ。違法性や偽造の断定は行わない。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence (or (:confidence patch) 0.85)})

(defn- propose-reindex
  [_db _request]
  {:op         :reindex-catalog
   :summary    "買い手向け検索インデックスの再構築を提案"
   :rationale  "公開審査を通過済みの出品のみを対象としたインデックス再構築。可視性の判断そのものは変更しない。"
   :cites      []
   :effect     :propose
   :value      {}
   :confidence 0.95})

(defn- propose-listing-concern
  [_db {:keys [listing-id patch]}]
  {:op         :flag-listing-concern
   :listing-id listing-id
   :summary    (str listing-id " の出品に関する懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "観察された懸念事実の報告のみ。違法性・真贋の判断は行わず、常に人間の確認を要する。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence (or (:confidence patch) 0.8)})

(defn infer
  [db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :draft-listing        (propose-draft db request)
                   :publish-listing      (propose-publish db request)
                   :suppress-listing     (propose-suppress db request)
                   :reindex-catalog      (propose-reindex db request)
                   :flag-listing-concern (propose-listing-concern db request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Clear before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually determined the goods are counterfeit and found the seller in violation")
      proposal)))

(defn trace [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :listing-id (:listing-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
