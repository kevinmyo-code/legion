---
kind: research
date: 2026-10-09
topic: Plaid as the daily Bank of America pull
---

# Plaid for the daily BofA pull (research, 2026-10-09)

Kevin picked Plaid on 2026-10-09 ("cuz its free") after Teller fell through. This file checks
whether it holds. Pages were fetched raw on 2026-10-09 (curl, HTML stripped), not read from search
snippets. Support-center articles carry an "Updated" stamp, quoted where it matters.

Tags: **[confirmed]** = read on a Plaid primary page. **[third-party]** = read on a non-Plaid page.
**[unconfirmed]** = could not verify, treat as open. **[reasoned]** = my inference, not stated.

## Verdict

**Go, conditionally.** The free tier is called the **Trial plan**. It is six months old, Plaid's own
pages were updated this week, and nothing found says it is ending. It gives real BofA data,
Transactions included, with no business registration. Three caveats:

1. **10 Items, lifetime, per Plaid team.** `/item/remove` does not give a slot back. Re-linking by
   mistake burns a slot. This is the real limit, not API calls.
2. **The tier is new and free.** Teller's free tier died because "very popular with individual
   developers" did not pay the bills. Plaid can change Trial terms too. Keep the CSV path alive.
3. **Eligibility is one-shot.** A Plaid team that ever had Limited Production, or applied for paid
   Production, cannot get Trial. Upgrading off Trial cannot be undone.

---

## 0. Step 0: shutdowns, sunsets, price changes (2025-2026)

Searched: Plaid changelog (full page, every 2024-2026 entry grepped), Plaid support center, Plaid
pricing page, Hacker News (Algolia API, all Plaid stories and comments since 2025-04), web news.

| Date | What happened | Source |
|---|---|---|
| 2024-06-20 | Development environment decommissioned, replaced by Limited Production | [changelog, 2024-10-09 entry](https://plaid.com/docs/changelog/) [confirmed] |
| 2025-07 to 2025-09 | JPMorgan starts charging aggregators for data; Plaid signs a paid deal with JPM. Plaid said it would not pass the cost on. | [Payments Dive](https://www.paymentsdive.com/news/plaid-to-pay-for-jpmorgan-data-open-banking-fintechs/760192/), [Bloomberg Law](https://news.bloomberglaw.com/banking-law/jpmorgan-plaid-data-fee-deal-shifts-open-banking-battlefield) [third-party] |
| 2025-12-10 | Announced BofA API migration; new-API BofA Items get a **12-month consent expiry** | [changelog](https://plaid.com/docs/changelog/) [confirmed] |
| 2026-02-18 | BofA migration dates: new API for new Items Feb to mid-Mar 2026; old Items disconnected mid-Mar to **late Oct 2026** unless sent through update mode | [changelog](https://plaid.com/docs/changelog/) [confirmed] |
| **2026-04-15/16** | **Limited Production closed to new US/CA sign-ups. Trial plan introduced**: free Production, 10 Items. Existing Limited Production teams keep it. | [changelog 2026-04-16](https://plaid.com/docs/changelog/), [support: Sandbox vs Trial vs LP](https://support.plaid.com/hc/en-us/articles/16110110883479-How-are-Sandbox-Production-Trial-plan-and-Limited-Production-different) (updated 2026-10-06) [confirmed] |
| 2026-04-19 | Developer Policy effective date (current version) | [plaid.com/legal](https://plaid.com/legal/) [confirmed] |
| 2026-05-20 | "Trial plan customers can now get immediate access to all OAuth institutions." | [changelog](https://plaid.com/docs/changelog/) [confirmed] |
| 2026-10-06/07 | Trial support articles re-edited; still describe Trial as live | [What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan) (updated 2026-10-07) [confirmed] |
| 2026-10-08 | Latest changelog entry. No Trial change. Beta of "modernized" client libraries announced (Go first). | [changelog](https://plaid.com/docs/changelog/) [confirmed] |

**Result:** no shutdown, sunset, price change or end of the Trial tier found, through 2026-10-09.
The one free-tier change in the window is the April 2026 swap of Limited Production for Trial.
HN: no thread reports Trial being pulled; one 2026-06-14 comment mentions "a hobbyist tier plaid
account" in use. [third-party]

**Stale page warning, proving the brief's point:** [plaid.com/pricing](https://plaid.com/pricing/)
FAQ still says "Our Limited Production service allows you to make up to 200 API calls". That tier
is closed to new sign-ups. The pricing page is not the authority; the billing docs and support
articles are.

**Side finding on Teller [traced]:** the HN post "Teller.io shuts down at the end of the week"
(item 48841633) is timestamped **2026-07-09 06:12 UTC** by the HN API, not mid-September. The
Teller research file's correction banner says mid-September. One of the two is wrong; the HN
timestamp is the primary record.

---

## 1. The free tier: Trial plan

| Question | Answer | Source |
|---|---|---|
| Name | **Trial plan**. (Development died 2024; Limited Production closed to new US/CA teams 2026-04-15.) | [billing docs](https://plaid.com/docs/account/billing/) [confirmed] |
| Who | US/Canada developers, **new team created on/after 2026-04-15**, no existing Production or Limited Production account | [What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan) [confirmed] |
| Item limit | **10 Production Items, lifetime.** `/item/remove` does not free a slot. Every access token created in Production counts, duplicates included. | [billing docs: Trial plan limitations](https://plaid.com/docs/account/billing/) [confirmed] |
| API calls | **Not capped** on connected Items | [What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan) [confirmed] |
| Products | Auth, **Transactions (incl. Recurring + Refresh)**, **Balance**, Identity, Assets, **Liabilities**, Investments, **Statements** | same, and [billing docs](https://plaid.com/docs/account/billing/) [confirmed] |
| Not included | Identity Verification, Signal, Transfer, Consumer Report, Monitor, Protect, Income, Investments Move, Enrich, Layer, Payment Initiation | [What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan) [confirmed] |
| Real data | **Yes**, Production environment | same [confirmed] |
| Expiry | **None stated** on any page read. No end date, no day count. | billing docs, both support articles [confirmed absent]; that it never expires is **[unconfirmed]** |
| Inactivity | Plaid may deactivate a Dashboard account unused for **three months** (daily pulls keep it in use) | [Developer Policy, Account Deactivation](https://plaid.com/legal/) [confirmed] |
| Sign-up | Create Dashboard account, verify email, then the Trial application at `dashboard.plaid.com/trial-plan` (or `dashboard.plaid.com/signup`). Accept the **Master Services Authorization Agreement** plus security attestations. **No business registration, no security questionnaire, no sales process.** | [billing docs](https://plaid.com/docs/account/billing/), [What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan) [confirmed] |
| "Personal use" option | Sign-up offers "Personal use (I want to use Plaid's APIs to build something for fun)" | search snippet of a Plaid support page; not seen on a fetched page **[unconfirmed]** |
| Identity check | "approved automatically after identity verification" (what ID is asked for is not stated) | [What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan) [confirmed]; the exact ID asked **[unconfirmed]** |
| Approval time | **Mostly automatic.** If flagged for manual review: follow-up in **2-3 business days** by email | same [confirmed] |
| Billing doc's framing | "Most appropriate for hobbyist use, or for evaluating and learning more about Plaid." | [billing docs](https://plaid.com/docs/account/billing/) [confirmed] |
| One-way doors | Applying for paid Production locks you out of Trial forever. A team with pending/approved Production or Limited Production "cannot" get Trial; "no self-service way to switch". | [upgrade article](https://support.plaid.com/hc/en-us/articles/39917307426967-How-do-I-upgrade-from-the-Trial-plan-to-a-paid-Production-plan), [pricing plans article](https://support.plaid.com/hc/en-us/articles/16110502116887-What-are-Plaid-s-prices-and-pricing-plans-and-how-do-they-differ) [confirmed] |
| Upgrade billing trap | A subscription product (Transactions, Liabilities) added to an Item during Trial **starts billing the day you upgrade**, for every Item still connected | [billing docs](https://plaid.com/docs/account/billing/) [confirmed] |

**The MSA text itself was not read.** It is shown at sign-up and is not on
[plaid.com/legal](https://plaid.com/legal/). A copy filed with the SEC (version dated 2023-11-27)
exists on a third-party mirror; I did not treat it as current. **[unconfirmed]**

**What 10 Items means for LEGION [reasoned]:** one Item = one login at one institution, covering
the accounts picked under that login
([Trial article](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan):
"An Item represents one end-user connection to a financial institution"). Kevin's BofA checking
plus cards under one BofA login is **one Item**. Mia's own BofA login would be a second. Kevin's
parents on the same engine (ADR 0045) would spend Kevin's slots unless they bring their own Plaid
team. A consent renewal through update mode does **not** create a new Item (§2). Budget: the
slots go on mistakes (re-linking instead of update mode, test links in Production), not on years.
Test everything in Sandbox first; Sandbox Items are unlimited and free
([support: Sandbox vs Trial](https://support.plaid.com/hc/en-us/articles/16110110883479-How-are-Sandbox-Production-Trial-plan-and-Limited-Production-different)).

---

## 2. Bank of America

### Supported on Trial, checking and credit cards
- BofA is named as included on Trial with **immediate** OAuth access: "immediate access to almost
  all OAuth institutions, including Bank of America, Chase, Wells Fargo..."
  ([What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan),
  [Can I use Plaid for free](https://support.plaid.com/hc/en-us/articles/16194695660311-Can-I-use-Plaid-for-free))
  [confirmed]
- Transactions covers depository (checking, savings) and credit (cards)
  ([Transactions intro](https://plaid.com/docs/transactions/)) [confirmed].
- BofA history Plaid can fetch: **checking/savings: last 18 months. Personal credit cards: the
  current partial cycle plus 11 previous statement cycles.**
  ([support: BofA history](https://support.plaid.com/hc/en-us/articles/25348952076567-Why-is-the-full-transaction-history-missing-for-Bank-of-America-accounts),
  updated 2026-06-01) [confirmed]. That this works for Kevin's specific card products on a Trial
  team is **[unconfirmed]** until one real link is done.

### The OAuth flow the user sees
1. In Link, pick Bank of America. Link opens BofA's own site in a **pop-up** (desktop web) or new
   tab (mobile web). [confirmed, [OAuth guide](https://plaid.com/docs/link/oauth/)]
2. Log in **on BofA's site**, with whatever 2FA BofA asks for. Plaid never sees the password. [reasoned from OAuth; standard]
3. On the new BofA API, **BofA hosts its own account-select screen**: tick checking and each card
   there. [confirmed, [OAuth guide, Bank of America section](https://plaid.com/docs/link/oauth/)]
4. Back in Link, `onSuccess` fires with a `public_token`. [confirmed]

### Registration on Trial
- "You don't need to complete the OAuth registration requirements in the Dashboard until you
  upgrade to a paid plan." ([What is the Trial plan](https://support.plaid.com/hc/en-us/articles/39994173227159-What-is-the-Plaid-Trial-plan))
  The OAuth guide says the same: "If you are on a Trial plan, you do not need to complete these
  requirements until you upgrade" ([OAuth guide](https://plaid.com/docs/link/oauth/)). [confirmed]
- **No separate app registration with BofA.** Plaid holds the BofA relationship. [confirmed, by
  the two quotes above; no BofA-side step is described anywhere]
- **Redirect URI:** for desktop and mobile **web**, OAuth works **without** a redirect URI (pop-up).
  A redirect URI is needed only so mobile users inside an in-app browser (webview) can finish.
  If used: **HTTPS only**, added to the Dashboard's **Allowed redirect URIs** list, no `#`, no
  query params, no custom schemes; reinitialize Link on that page with `receivedRedirectUri`.
  ([OAuth guide](https://plaid.com/docs/link/oauth/)) [confirmed]
- Chase needs the Security Questionnaire "unless you are on a Trial plan"; PNC needs the latest MSA
  "unless you are on a Trial plan". No such carve-out text exists for BofA because BofA has no extra
  gate listed. [confirmed]
- No "BofA access needs approval" delay was found for Trial. The only named slow institutions are
  Fidelity and Schwab. ([OAuth guide](https://plaid.com/docs/link/oauth/),
  [changelog 2026-09-11](https://plaid.com/docs/changelog/)) [confirmed]

### 12-month consent and re-consent
- New-API BofA Items carry a **12-month consent expiration**. ([OAuth guide](https://plaid.com/docs/link/oauth/),
  [changelog 2025-12-10](https://plaid.com/docs/changelog/)) [confirmed]
- Read the date from `/item/get` field `consent_expiration_time`. [confirmed]
- **`PENDING_DISCONNECT`** webhook fires **7 days before** disconnect, `reason` =
  `INSTITUTION_TOKEN_EXPIRATION` (consent) or `INSTITUTION_MIGRATION`, with `disconnect_time`.
  (US/CA uses `PENDING_DISCONNECT`; `PENDING_EXPIRATION` is UK/EU only.)
  ([Items API](https://plaid.com/docs/api/items/)) [confirmed]
- Miss it and the Item goes `ITEM_LOGIN_REQUIRED`. Fix: **Link in update mode** (a Link token
  created with the existing `access_token`). The user redoes the BofA login and 2FA.
  ([update mode](https://plaid.com/docs/link/update-mode/)) [confirmed]
- **Update mode keeps the same `access_token`; no new exchange.** So re-consent does not spend a
  Trial Item slot. ([update mode](https://plaid.com/docs/link/update-mode/): "An Item's access_token
  does not change when using Link in update mode") [confirmed for the token; that the cap counts
  tokens, not consent events, is from the billing docs; the combination is **[reasoned]**]
- Kevin's net: **one BofA 2FA login a year**, plus any time BofA revokes the token early.
- Migration window ends late October 2026; it only hits Items created on the old API. A new link
  today lands on the new API. [confirmed for dates; that new links are on the new API: "From
  February through mid March 2026, the new API is being gradually rolled out for new Items"]

---

## 3. Integration mechanics (server-side daily pull)

### Plaid Link on our own web app
- Allowed. Load `https://cdn.plaid.com/link/v2/stable/link-initialize.js` **from Plaid's CDN**
  (never bundled or self-hosted; unversioned, always latest). CSP needs `cdn.plaid.com` for
  script/frame and `production.plaid.com` for connect.
  ([Link web](https://plaid.com/docs/link/web/)) [confirmed]
- Server makes the Link token (`/link/token/create`) with `products: ["transactions"]`,
  `transactions.days_requested` (default 90, **max 730**, only settable on first init), `webhook`,
  `user.client_user_id`, and `redirect_uri` if used.
  ([Transactions intro](https://plaid.com/docs/transactions/), [OAuth guide](https://plaid.com/docs/link/oauth/)) [confirmed]
- Android: the native SDK needs the package name on the Dashboard allowlist, and
  `android_package_name` instead of `redirect_uri` ([OAuth guide](https://plaid.com/docs/link/oauth/))
  [confirmed]. Web Link on the Django web app avoids that per-household registration. [reasoned]

### public_token to access_token
- `onSuccess` gives a short-lived `public_token`; server calls `/item/public_token/exchange` for
  the permanent `access_token` + `item_id`. ([Transactions intro](https://plaid.com/docs/transactions/)) [confirmed]
- **Persist the access token.** Losing it on Trial loses a slot for good. ([billing docs](https://plaid.com/docs/account/billing/)) [confirmed]

### `/transactions/sync`
([API reference](https://plaid.com/docs/api/products/transactions/), [transaction states](https://plaid.com/docs/transactions/transactions-data/)) [confirmed unless tagged]
- **Cursor model.** First call: no cursor. Store `next_cursor`. Loop while `has_more`. `count`
  default 100, max 500. On `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION`, restart the whole loop
  from the cursor of the first page, not just the failed call.
- Returns `added`, `modified`, `removed` (removed = ids only), plus `accounts` with **cached**
  balances, plus `transactions_update_status`.
- The first `/transactions/sync` call is also what switches on `SYNC_UPDATES_AVAILABLE` webhooks.
- Optional `account_id` makes a separate cursor stream per account.
- **Pending vs posted:** posting shows up as the pending id in `removed` and a new posted row in
  `added`, possibly on different pages of the same update. The posted row's
  **`pending_transaction_id`** names the pending row it replaced, if Plaid matched them; null
  otherwise. Name and amount may change (tips). A pending row may vanish with no posted successor
  (authorization holds). Posted rows can still be modified or removed (refunds, recategorization,
  "transaction churn"). Pending to posted: typically 1-5 business days, rarely up to 14.
- **Dates:** `date` = transaction date for pending, **posted date for posted**.
  `authorized_date` = when it happened. Plus `authorized_datetime` / `datetime`.
- **Amount sign:** "Positive values when money moves out of the account; negative values when money
  moves in. For example, debit card purchases are positive; credit card payments, direct deposits,
  and refunds are negative." Same rule for checking and cards.
- **Amount type is a JSON `number`** (a float), with `iso_currency_code`. [confirmed]
- Raw bank text: `original_description`, only if `options.include_original_description: true`.
- Freshness: Plaid polls the bank **1-4 times a day**. `/transactions/refresh` forces it (Trial
  includes Transactions Refresh).

### History
- Initial pull: `days_requested`, default 90, max 730. After that Plaid keeps everything new for the
  life of the Item. ([Transactions intro](https://plaid.com/docs/transactions/)) [confirmed]
- BofA caps the backfill below 730: 18 months checking, 11 closed cycles on personal cards (§2).

### Webhooks
([Transactions webhooks](https://plaid.com/docs/transactions/webhooks/), [Items API](https://plaid.com/docs/api/items/), [webhook verification](https://plaid.com/docs/api/webhooks/webhook-verification/)) [confirmed]

| Webhook | Meaning |
|---|---|
| `TRANSACTIONS: SYNC_UPDATES_AVAILABLE` | New changes; call sync. Carries `initial_update_complete` (30 days ready) and `historical_update_complete` (full history ready). |
| `ITEM: ERROR` | e.g. `ITEM_LOGIN_REQUIRED` with `error_code_reason` such as `OAUTH_INVALID_TOKEN` |
| `ITEM: PENDING_DISCONNECT` | 7 days to disconnect (consent expiry or migration) |
| `ITEM: LOGIN_REPAIRED` | Healed without our update mode |
| `ITEM: NEW_ACCOUNTS_AVAILABLE` | New account seen (e.g. a new card); add via update mode |
| `ITEM: USER_PERMISSION_REVOKED` / `USER_ACCOUNT_REVOKED` | User revoked access (may not fire if revoked at the bank) |

- **Verification is optional but available:** header `Plaid-Verification` is an **ES256 JWT**.
  Reject if `alg` is not ES256; fetch the JWK by `kid` from `/webhook_verification_key/get`; verify;
  check `iat` is under 5 minutes old; compare `request_body_sha256` to the SHA-256 of the raw body.
- A cron-only daily pull works without webhooks: call sync once a day; `transactions_update_status`
  is there for missed webhooks. New 2026-10-08: beta `/beta/webhook_events/list` for missed
  webhooks ([changelog](https://plaid.com/docs/changelog/)). [confirmed]
- **Webhooks are not the only consent warning:** `/item/get` gives `consent_expiration_time`, so a
  daily job can see expiry coming even if a webhook is lost. [confirmed]

### Rate limits (Production, per Item / per client)
([rate limits](https://plaid.com/docs/errors/rate-limit-exceeded/)) [confirmed]

| Endpoint | Per Item | Per client |
|---|---|---|
| `/transactions/sync` | 50/min | 2,500/min (500/min with empty cursor) |
| `/transactions/refresh` | 2/min, 120/hour, 2,880/day | 100/min |
| `/item/get` | 15/min | 5,000/min |
| `/accounts/balance/get` | 5/min, 30/hour | 1,200/min |
| `/liabilities/get` | 30/min | 10,000/min |
| `/statements/list` | n/a | 100/min |
| `/statements/download` | n/a | 50/min |
| `/webhook_verification_key/get` | n/a | 500/min |

Fixed windows, not rolling; "subject to change at any time". A daily pull is far below all of these.

### client_id / secret storage
- Developer Policy: never publish or share Client ID or secret with third parties; keep them
  **encrypted in storage and in transit**. Secrets can be rotated from the Dashboard (Keys; old
  secret works until deleted). ([Developer Policy](https://plaid.com/legal/), [Dashboard security](https://plaid.com/docs/account/security/)) [confirmed]
- End User Data must use "modern and industry standard cryptography" at rest and in transit; GLBA
  Safeguards Rule named. Access tokens are the thing to protect most. [confirmed policy; that access
  tokens are the key asset is **[reasoned]**]
- Fit: per-household keys in the household's own secret store (Cloud Run: Secret Manager). Each
  household its own Plaid team, matching clone-and-run. [reasoned]

### Python client
- **`plaid-python`**, official, MIT. Latest **45.0.0, released 2026-10-02**. Majors ship roughly
  monthly (41.0.0 2026-07-23 through 45.0.0). **Pin it.** ([PyPI](https://pypi.org/project/plaid-python/),
  [libraries](https://plaid.com/docs/api/libraries/)) [confirmed]
- Plaid announced a "modernized" client-library beta on 2026-10-08 (Go first, other languages by
  request) ([changelog](https://plaid.com/docs/changelog/)). Expect a Python rewrite at some point.
  [confirmed for the announcement; Python timing **[unconfirmed]**]

---

## 4. Data-use terms for personal use, and what Plaid stores

- **Nothing found forbids a personal household app.** Plaid's own billing docs call Trial "most
  appropriate for hobbyist use". The Developer Policy's prohibitions are selling End User Data,
  unlawful use, storing data beyond what is needed and authorized, competitive evaluation or
  building a substitute service, redistributing the Services or data without permission, and
  breaching security. ([Developer Policy, Prohibited Conduct](https://plaid.com/legal/)) [confirmed]
- When to leave Trial, per Plaid: past 10 Items, need non-Trial products, "building a commercial
  product or service". ([upgrade article](https://support.plaid.com/hc/en-us/articles/39917307426967-How-do-I-upgrade-from-the-Trial-plan-to-a-paid-Production-plan)) [confirmed]
- **Clone-and-run caveat [reasoned]:** a stranger's household brings its own Plaid team; never ship
  or share Kevin's keys (Policy forbids sharing Client ID/secret). Kevin's parents connecting
  through Kevin's team makes them end users of Kevin's app; the policy allows end users, and nothing
  read says they must be the developer himself. Not checked against the MSA text **[unconfirmed]**.
- **What Plaid keeps:** data it pulls from the bank about the connected accounts; it uses data to
  run and improve its services, and uses aggregated / de-identified data for any lawful purpose.
  When the developer removes the connection, Plaid's systems "are designed to automatically delete"
  personal data, with exceptions (other apps still connected, legal holds, fraud, de-identified
  data). Stored in AWS US regions. Users can see and cut connections at **my.plaid.com**.
  ([End User Privacy Policy, effective 2025-12-08](https://plaid.com/legal/)) [confirmed]
- Developers who link **their own** accounts to test are covered by the End User Privacy Policy
  for that data ([plaid.com/legal](https://plaid.com/legal/), All Audiences Privacy Statement)
  [confirmed].
- The MSA (accepted at sign-up) was not read; see §1. **[unconfirmed]**

---

## 5. Costs past the free tier

- Plans: **Pay-as-you-go** (no minimum, no commitment, sign up in Dashboard), **Growth** (annual
  commitment, minimum spend), **Custom/Scale** (higher minimums). ([billing docs](https://plaid.com/docs/account/billing/)) [confirmed]
- **Prices are not published.** "Pricing information for Pay-as-you-go and Growth plans is available
  as part of the Production access request flow." ([pricing plans article](https://support.plaid.com/hc/en-us/articles/16110502116887-What-are-Plaid-s-prices-and-pricing-plans-and-how-do-they-differ), updated 2026-10-06) [confirmed]
- Models that matter here ([billing docs](https://plaid.com/docs/account/billing/)) [confirmed]:
  - **Transactions, Liabilities:** monthly **subscription per Item**, billed while the Item exists
    even if no calls succeed (e.g. Item in error). Calendar months UTC, not pro-rated. Stopping
    it means `/item/remove`.
  - **Transactions Refresh, Balance:** per successful request.
  - **Statements:** per Item at creation, by number of statements in the range; Statements Refresh
    per request.
- Ballpark, **[third-party / unconfirmed]**: ~$0.30 per Item per month for Transactions is a
  community-reported figure; one HN commenter (2026-09-26) says "I think i pay 50 cents a month or
  something". For one household (1-3 BofA Items) that is likely a few dollars a month, but the real
  number is only visible inside the Production application. Also: going paid is one-way, and every
  Trial Item already carrying Transactions starts billing at once.
- Production application asks for: business type and details, industry, app details, products and
  use cases, **beneficial ownership details**, plan and billing details. Most are auto-reviewed;
  the security questionnaire unlocks after approval. ([upgrade article](https://support.plaid.com/hc/en-us/articles/39917307426967-How-do-I-upgrade-from-the-Trial-plan-to-a-paid-Production-plan)) [confirmed]

---

## 6. Fit with LEGION's gate (observations, not a design)

- **Plaid rows have no printed anchor**, so under CLAUDE.md §4 they are rule 7 material: extraction
  is deterministic (an API, no LLM), tag `UNRECONCILED`, say so in words, delete when a gated
  statement covers the same account and dates. [reasoned]
- **Pending rows mutate and vanish.** Only posted rows are stable enough to compare against a
  statement; pending rows are replaced via `removed` + `pending_transaction_id`. The supersede
  window should use the **posted `date`**, since statements are cut by posting date. [reasoned]
- **Amount is a float.** Convert to `Long` cents through a decimal parse of the JSON text, never
  float arithmetic (§4 rule 3). Sign: positive = money out, both account types. [confirmed sign; the
  conversion advice is reasoned]
- **New option, worth a ruling: Plaid Statements is on Trial and supports BofA** (depository only:
  checking/savings, not cards). It returns the bank's own PDF statement, the same document the
  existing BofA statement path already gates on. That could replace the manual monthly checking
  statement download. Cards would still need their PDF from BofA. ([Statements intro](https://plaid.com/docs/statements/)) [confirmed for support list; that BofA statements on Trial work for Kevin **[unconfirmed]**]
- **Liabilities** gives a card's `last_statement_balance` and `last_statement_issue_date`
  ([Liabilities API](https://plaid.com/docs/api/products/liabilities/)) [confirmed]. That is a
  bank-reported number, not a printed document; whether it may serve as an anchor is a §4 question
  for Kevin, not something this file decides.
- `tools/bofa_pull.py` is not tracked in git on this machine (searched `git ls-files`). [confirmed]

---

## 7. What Kevin must do himself

1. **Check first:** has he ever created a Plaid Dashboard team? If one exists with Limited
   Production or a Production application, Trial is not available on it. Whether a fresh team on a
   new email qualifies is **[unconfirmed]**; ask Plaid support before burning anything.
2. Sign up at `dashboard.plaid.com/signup`, verify email, pick personal use if offered.
3. Fill the Trial application (`dashboard.plaid.com/trial-plan`): accept the MSA and the security
   attestations; pass the identity verification. Expect instant approval, or 2-3 business days.
4. **Do not click "Get Production access".** It ends Trial for good.
5. Copy `client_id` and the **Production** secret (and the Sandbox secret for testing) from
   Dashboard > Keys into the household's secret store. Turn on Dashboard 2FA.
6. If Link will run from phones' browsers: add the web app's HTTPS OAuth page to **Allowed redirect
   URIs** in the Dashboard. Desktop web needs nothing.
7. **No BofA app registration, no OAuth compliance-center registration** on Trial.
8. Link BofA **once**: BofA login and 2FA, then tick checking **and every card** on BofA's account
   screen. Never re-link to fix a broken connection; use the app's re-connect (update mode).
9. Once a year (and on any `PENDING_DISCONNECT` or `ITEM_LOGIN_REQUIRED`): redo BofA login + 2FA in
   update mode.

---

## 8. Open risks

| Risk | Why it matters | Mitigation hint |
|---|---|---|
| Trial terms change | Six months old, free, aimed at hobbyists, the same profile Teller had | Keep the CSV/statement path working; Plaid rows are provisional anyway |
| 10-Item lifetime cap | Mistakes are permanent; parents on Kevin's engine eat slots | Sandbox first; update mode, never re-link; duplicate-Item guard |
| Eligibility unknown | An old Kevin Plaid team would block Trial | Step 1 of §7 |
| MSA not read | Terms accepted at sign-up could restrict use | Kevin reads it at sign-up |
| No expiry stated | Absence of an end date is not a promise | Watch the changelog and support "Updated" stamps |
| BofA early revocation | Bank can invalidate OAuth tokens before 12 months | `ITEM: ERROR` handling + `/item/get` daily |
| Bank data fees | JPMorgan charges aggregators since 2025; CFPB §1033 rule enjoined and being rewritten (third-party reporting). If BofA follows, free tiers are the first thing to go. | Same as row 1 |
| Client library churn | plaid-python majors monthly; a modernized rewrite announced | Pin the version |
| Pending churn | Pending rows rename, re-amount, vanish | Store posted-only as provisional, or treat pending as display-only (Kevin's call) |
| Float amounts | Money-as-float breaks §4 rule 3 | Decimal parse at ingestion |

## Assumptions ledger

- Trial exists, 10 Items, products, BofA immediate access, no OAuth registration on Trial: **confirmed** (Plaid support + docs, fetched 2026-10-09).
- No Trial shutdown or price change through 2026-10-09: **confirmed absent** in changelog, support articles, HN search; future stability **reasoned risk**.
- Trial never expires: **unconfirmed** (no expiry stated anywhere).
- BofA checking + credit card both pull on a Trial team: **confirmed by docs**, **unconfirmed on a real link**.
- Re-consent via update mode does not spend an Item slot: **reasoned** from "access_token does not change" + "access tokens count".
- Transactions price after Trial (~$0.30-0.50/Item/month): **third-party, unconfirmed**.
- Teller shutdown date 2026-07-09 not mid-September: **traced** (HN API timestamp).
