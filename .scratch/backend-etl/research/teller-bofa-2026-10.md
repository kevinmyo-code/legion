> **CORRECTION 2026-10-09: Teller is shut down.** Teller withdrew its API (Hacker News post dated 2026-07-09) ("not able to attract enough large customers"; service ran to the end of that week). The go verdict below is WRONG: the research read stale docs and the live site footer and missed the shutdown. Sources: https://news.ycombinator.com/item?id=48841633 , https://www.quiltt.io/blog/teller-api-shutdown-how-to-migrate-teller-alternatives . Do not build on Teller.

---
kind: research
date: 2026-10-09
topic: Teller.io as the daily Bank of America pull
---

# Teller.io for the daily BofA pull (research, 2026-10-09)

Kevin picked Teller on 2026-10-09. This file checks whether it holds up. Primary sources were
fetched on 2026-10-09; nothing here rests on a search snippet alone.

Tags: **[confirmed]** = read on a primary source page. **[traced]** = read in third-party code that
calls Teller, not in Teller's own docs. **[unconfirmed]** = could not verify; treat as open.

## Verdict

**Go, conditionally.** Teller is live, documented and maintained in 2026, BofA is listed, and the
free developer tier (100 live connections, real data) covers one household easily. Three things stay
open until Kevin's own account and one real BofA enrollment settle them:

1. Whether self-serve sign-up is open (no sign-up link found; see §1).
2. How long a BofA enrollment really lasts (Teller publishes no number; see §2).
3. The amount sign for checking vs credit card (docs do not say; see §4.6).

---

## 1. Is Teller alive and taking new developers?

**The dev.to article is not news.** It is an AI-generated "startup opportunity" post. It opens with a
hypothetical ("Imagine waking up to an email: 'Teller.io is shutting down at the end of the week.'")
and then states the shutdown as fact ("Teller.io's sudden shutdown leaves indie developers...")
with **no source, date or link**. It ends by promoting PainRadar.com. Published 2026-07-10 by an
account created 2026-06-29. **Category: unsourced shutdown claim. No evidence behind it.**
[confirmed: https://dev.to/ping_wang_ac166b4d1e4f29a/how-to-build-a-drop-in-replacement-for-tellerio-before-your-app-breaks-20fp]

Evidence Teller is alive in 2026:

- Docs footer reads "© Teller 2026". [confirmed: https://teller.io/docs/api/account/transactions]
- Docs carry content newer than 2024: a "Syncing Transactions" section with 2025 example dates, and a
  long free-tier rate-limit section. [confirmed: same URL; https://teller.io/docs/api]
- Homepage still offers the free developer tier and lists BofA. [confirmed: https://teller.io/]
- Dashboard login page is live. [confirmed: https://teller.io/session]
- GitHub: `examples` updated 2025-09-29, `teller-connect-react` 2025-03-18. [confirmed: https://github.com/tellerhq]
- Teller Connect script serves (HTTP 200). [confirmed: https://cdn.teller.io/connect/connect.js]

Not found:

- **No blog post since 2023-10-20.** No 2024-2026 announcement of shutdown, acquisition, pricing
  change or deprecation. [confirmed: https://teller.io/blog]
- **No public changelog.** `/changelog` and `/docs/changelog` both 404. [confirmed]
- **No public status page.** `status.teller.io` does not resolve; `teller.io/status` is 404. [confirmed]
- **No sign-up link anywhere on the site.** Homepage links only to "Sign in" (`/session`).
  `teller.io/signup` returns **404**, though Rutter's docs still say "Sign up at Teller Dashboard
  (https://teller.io/signup)". [confirmed 404; https://docs.rutter.com/platforms/banking/teller]
  The Jan 2025 archived homepage also had no sign-up link, so this is not necessarily new.
  **[unconfirmed] whether new developers can self-register today.** Kevin finds out in five minutes
  by trying; if there is no route, email Teller.
- **`api.teller.io` timed out on TCP connect from this laptop** (three AWS us-west-2 IPs), while
  `teller.io` and `cdn.teller.io` answered. Likely this machine's sandbox or network, not Teller.
  **[unconfirmed]** Retest from Cloud Run before building on it.

## 2. Bank of America

**Listed: yes.** The homepage lists "Chase, Bank of America, Wells Fargo, CreditOne, PNC, CapitalOne"
as instant-connect institutions. [confirmed: https://teller.io/]
**Products for BofA (checking + card, transactions + balance): [unconfirmed].** The public
`GET https://api.teller.io/institutions` lists products per bank without auth
[confirmed endpoint: https://teller.io/docs/api/institutions], but this machine could not reach
`api.teller.io`. Check from any other network:
`curl https://api.teller.io/institutions | jq '.[] | select(.name|test("America"))'`.

Account types the API models: `depository` (checking, savings, ...) and `credit` (`credit_card`).
[confirmed: https://teller.io/docs/api/accounts] So BofA checking and the BofA card arrive as
separate accounts under **one enrollment** (one BofA login). [confirmed: https://teller.io/docs/guides/quickstart]

**What Kevin goes through.** Teller Connect handles "credential validation, multi-factor
authentication, account selection, and error handling" inside the widget: pick bank, enter BofA
user and password, pass BofA's 2FA, pick accounts. [confirmed: https://teller.io/docs/guides/connect]
The exact BofA prompts (SMS code, app approval) are not documented. [unconfirmed]

**Enrollment lifetime. No published number.** What Teller does say:

- "Teller's connections are designed to be long-lasting. When a disconnection does occur, it's
  typically because something external changed - the user updated their banking password, the bank
  requires fresh MFA, or the institution needs the user to accept updated terms."
  [confirmed: https://teller.io/docs/guides/connect]
- "Teller refreshes your enrollments at least once per day." [confirmed: https://teller.io/docs/api/account/transactions]
- Disconnect reasons include `credentials_invalid` ("The end-user changed their authentication
  credentials"), `user_action.mfa_required`, `enrollment_inactive` ("disconnected due to
  inactivity", period not stated), `account_locked`, `web_login_required` (e.g. accept new terms),
  `captcha_required`. [confirmed: https://teller.io/docs/api/errors]
- Repair: reopen Connect with the `enrollmentId`; after re-auth "the existing enrollment is restored
  and the same accessToken continues to work." [confirmed: https://teller.io/docs/guides/connect]

So: **not "never", not a fixed period. Event-driven**: password change, bank-demanded MFA, new terms,
or inactivity. How often BofA demands fresh MFA from Teller is **[unconfirmed]**; the only real
answer is to enroll and log the `enrollment.disconnected` webhooks.

**Known BofA issues.** None found on Teller's site, blog or GitHub issues. **One industry signal to
watch:** Plaid announced that BofA moved aggregators to a new BofA API, rolled out Feb-Mar 2026,
with existing Plaid items disconnected mid-March to late October 2026, and **"Bank of America Items
on the new API will have a 12-month consent expiration applied."**
[confirmed: https://plaid.com/docs/changelog/ entries 2025-12-10 and 2026-02-18]
Whether Teller connects to BofA through that API (and inherits the 12-month expiry) or another way
is **not documented by Teller. [unconfirmed]** Plan for an annual re-consent at minimum.

## 3. Pricing and limits

| Tier | Terms | Source |
|---|---|---|
| Developer | "free for independent developers and teams prototyping ideas. Our developer tier includes 100 live connections. Provided that you never need more than that you won't owe us a penny." | https://teller.io/ |
| Development environment | Real bank data, your own or team credentials, **100 enrollments**, free, mTLS required | https://teller.io/docs/guides/environments |
| Production | Transactions $0.30 per enrollment per month; Balance $0.10 per call; Verify $1.50 per account; Identity $1.75 per call. Requires KYB (company details, beneficial owners) | https://teller.io/ ; https://teller.io/docs/guides/environments |

- An "enrollment" is one bank login and can hold several accounts, so BofA checking + card = 1 of 100.
  [confirmed: environments page]
- **Rate limits:** "Free-tier accounts are subject to rate limits. The exact thresholds are not
  publicly documented and cannot be adjusted." Exceeding returns **HTTP 429**; "back off and retry".
  [confirmed: https://teller.io/docs/api]
- **Past 100:** the homepage implies paying; the mechanics (hard block vs upsell) are not stated.
  [unconfirmed] Irrelevant at one household.
- "Failed requests don't generate billing events." [confirmed: https://teller.io/docs/api/errors]
- Developer ToS reserves the right to make features paid-only, with **30 days' notice** of price
  changes. [confirmed: https://teller.io/legal/developer/terms]

## 4. Integration mechanics

### 4.1 Teller Connect

- Script: `<script src="https://cdn.teller.io/connect/connect.js"></script>`; no `async`/`defer`.
- `TellerConnect.setup({ applicationId: "app_...", products: ["transactions","balance"], environment: "development", onSuccess, onExit, nonce, enrollmentId, selectAccount })`.
  `environment` is `sandbox | development | production`.
- `onSuccess` payload: `{ accessToken: "token_...", user: { id: "usr_..." }, enrollment: { id: "enr_...", institution: { name } }, signatures: [...] }`.
- With a server-generated `nonce`, `signatures` are ED25519 over
  `nonce.accessToken.userId.enrollmentId.environment`, verifiable with the app's Token Signing Key
  from the dashboard.
- Repair mode: pass `enrollmentId`; skips the bank picker.
  [all confirmed: https://teller.io/docs/guides/connect]
- It is a plain browser widget, so it can live in the web app or a one-off local HTML page; the
  page just has to hand `accessToken` + `enrollment.id` to the server. Allowed-origin rules for the
  page are **[unconfirmed]**; the official example runs from `localhost`
  [confirmed: https://github.com/tellerhq/examples].

### 4.2 API auth

- Base URL `https://api.teller.io/`. Dated versions; latest `2020-10-12`; default set in the
  dashboard, override with `Teller-Version` header. [confirmed: https://teller.io/docs/api]
- **mTLS client certificate is "required for all API requests that involve end-user data, i.e. all
  requests in `development` and `production`."** Optional in sandbox.
- Access token as HTTP Basic username, empty password: `curl --cert cert.pem --key key.pem -u ACCESS_TOKEN: https://api.teller.io/accounts`.
- "Access tokens are useless without a client certificate belonging to the application the user
  consented giving access to." Suspected key leak: revoke in dashboard, issue new.
  [all confirmed: https://teller.io/docs/api/authentication]
- Certificate expiry / rotation period: **not documented. [unconfirmed]**

**Kevin's own steps (the parts no agent can do):**

1. Create a Teller developer account (route unclear, see §1).
2. On sign-up, Teller downloads **`teller.zip`** containing the certificate and private key.
   "Check your downloads folder for teller.zip." If lost: revoke and create a new one on the
   **Certificates** page of the dashboard. [confirmed: https://teller.io/docs/guides/quickstart]
3. Copy the **Application ID** (`app_...`) from **Application Settings**. [confirmed: same]
4. If using the nonce check, copy the **Token Signing Key** from the dashboard. [confirmed: https://teller.io/docs/guides/connect]
5. If using webhooks, set the webhook URL and copy the **signing secret** in Application Settings.
   [confirmed: https://teller.io/docs/api/webhooks]
6. Put cert + key into the server's secret store (for Cloud Run, Secret Manager). Never in git.
7. Enroll BofA once through Connect in `development` with his real login and 2FA.

### 4.3 Endpoints

| Call | Endpoint | Source |
|---|---|---|
| List accounts | `GET /accounts` | https://teller.io/docs/api/accounts |
| One account | `GET /accounts/:account_id` | same |
| Revoke account / whole enrollment | `DELETE /accounts/:id`, `DELETE /accounts` | same |
| Balances | `GET /accounts/:account_id/balances` -> `ledger`, `available` (strings, nullable); "live, real-time" | https://teller.io/docs/api/account/balances |
| Transactions | `GET /accounts/:account_id/transactions`, `GET .../transactions/:transaction_id` | https://teller.io/docs/api/account/transactions |
| Institutions | `GET /institutions` (no auth, beta) | https://teller.io/docs/api/institutions |

Account fields: `id, enrollment_id, type, subtype, institution{id,name}, last_four, status (open|closed), currency, links`.
[confirmed: https://teller.io/docs/api/accounts]
Balance pricing in production is per call ($0.10), so a balance call is not free to spam once paid.
[confirmed: https://teller.io/]

### 4.4 Pagination and history depth

- Query params: `count`, `from_id` ("Returns transactions older than the one with this ID"),
  `start_date`, `end_date` (ISO 8601, both inclusive). [confirmed: https://teller.io/docs/api/account/transactions]
- "The initial call ... can sometimes time out with accounts that have an abnormally large number of
  transactions. Should this happen wait a few seconds and try again." [confirmed: same]
- **History depth: not documented. [unconfirmed]** No stated limit; likely whatever BofA exposes.
  Measure it on the first real pull.

### 4.5 Pending vs posted

- `status`: `posted` or `pending`. `running_balance` "is only present on transactions with a
  `posted` status." `details.processing_status` (`pending|complete`) is Teller's enrichment state,
  **not** bank posting state; do not confuse the two. [confirmed: same page]
- **ID across posting:** "Teller maintains stable transaction IDs. Occasionally, when a pending
  transaction changes significantly upon posting and cannot be matched to the original, it's created
  as a new record with a new ID. Account for this in your reconciliation." [confirmed: same page]
- "When a pending transaction posts, its date often changes to the posting date." Teller's own
  advice: re-pull a window **7-10 days** past the last sync and upsert by transaction id.
  [confirmed: same page]
- What happens to the orphaned pending row when a new id appears is **not stated**; presumably it
  stops being returned. [unconfirmed] So: pending rows usually keep their id, sometimes do not, and
  the replace-on-repull pattern must delete pending rows that vanish from the window.

### 4.6 Transaction id stability and amount sign

- IDs: stable, with the pending-to-posted exception above. [confirmed]
- `amount` is "The signed amount of the transaction as a string." **Teller does not document which
  direction is negative, or whether it differs between depository and credit.** [confirmed absence]
  The docs' own example shows an "ATM Withdrawal" as `"42.47"` (positive), which does not settle it.
- Maybe Finance's archived Teller integration flips the sign for asset (depository) accounts only
  and leaves credit accounts as-is before storing in its outflow-positive convention, which implies
  **depository debits arrive negative and credit-card charges arrive positive**.
  [traced: https://github.com/maybe-finance/maybe-archive/blob/main/libs/server/features/src/providers/teller/teller.etl.ts, `_extractTransactions`]
  **[unconfirmed] against Teller.** Verify on the first real BofA pull against a known purchase
  and a known card payment before any row is written.
- Amounts are strings: parse straight to `Long` cents, never through a float (CLAUDE.md §4 rule 3).

### 4.7 Webhooks

- Events: `enrollment.disconnected` (payload `enrollment_id`, `reason`), `transactions.processed`
  (payload includes "an array of categorized transactions"), `account.number_verification.processed`,
  `webhook.test`. Envelope: `id, type, timestamp, payload`.
- Signature header: `Teller-Signature: t=<unix ts>,v1=<sig>,v1=<sig>...`. Signed message is
  `<t>.<raw JSON body>`; HMAC-SHA256 with the signing secret; **reject if `t` older than 3 minutes**.
  Multiple `v1` values appear during secret rotation (old and new both sign during an expiry window).
- Retry policy: **not documented. [unconfirmed]**
  [all confirmed except retry: https://teller.io/docs/api/webhooks]
- Webhooks are optional: a daily cron that pulls a 10-day window works without them. The
  `enrollment.disconnected` webhook is the useful one, because it is how LEGION learns it needs Kevin.

### 4.8 Environments

| Env | Data | Enrollments | Cost | mTLS |
|---|---|---|---|---|
| sandbox | Simulated, "never connects to real financial institutions" | Unlimited | Free | Not required |
| development | **Real bank data**, your own credentials | 100 | Free | Required |
| production | Real, end users | Unlimited | Paid, KYB | Required |

[confirmed: https://teller.io/docs/guides/environments]
Sandbox users: `username` (instant success), `otp` (OTP code `0000`), `challenge` (answer `blue`),
`disconnected` (enrolls, then disconnects on first request); password `password`.
[confirmed: https://teller.io/docs/guides/sandbox]
**LEGION's household use sits in `development` permanently.** That is the tier the homepage calls
free for independent developers. Whether Teller is fine with a dev app running indefinitely as a
personal tool is not stated either way. [unconfirmed]

## 5. Data use and privacy

- End User Privacy Policy (last revised 2021-11-10): Teller "may collect ... your username and
  password, or a security token"; collects account and transaction data; "We do not sell your End
  User Personal Data"; may share with developers, vendors, and in business transfers.
  **Retention period and whether credentials are stored encrypted: not stated.** [confirmed: https://teller.io/legal/user/privacy]
  Teller must hold some reusable credential or session to refresh daily without Kevin; the form it
  keeps is **[unconfirmed]**.
- Developer Terms (last revised 2021-06-18): must be 18+; use "solely for your internal business
  purposes"; no reselling or making the Services available to third parties; Teller may terminate
  "at any time, with or without cause"; price changes on 30 days' notice. **No clause addresses
  personal or household use explicitly.** [confirmed: https://teller.io/legal/developer/terms]
  Reading: a household app for Kevin and Mia's own accounts fits "independent developers" on the
  homepage; it is not a ruling by Teller. [reasoned]
- SOC 2 Type 2 is listed under Enterprise; the 2021 blog says Teller is SOC 2 Type 2 compliant.
  [confirmed: https://teller.io/ ; https://teller.io/blog]
- Revocation: `DELETE /accounts` removes access to the whole enrollment. [confirmed: https://teller.io/docs/api/accounts]

## 6. Fallbacks, brief

| | Teller (dev tier) | SimpleFIN Bridge | Plaid Trial |
|---|---|---|---|
| Cost | Free, 100 enrollments | $1.50/mo or $15/yr, 25 institutions | Free, **10 Items lifetime** (removing does not free a slot) |
| BofA | Listed | Via MX upstream; BofA listing **[unconfirmed]** (search page is JS) | Yes, OAuth; Trial gets "immediate access to all OAuth institutions" (2026-05-20) |
| Refresh | "at least once per day" | Once a day, ~24 h | Product-defined |
| History | Not documented | At most 90 days per request; "varies for each institution" | Not checked |
| Re-auth | Event-driven, no number | Not documented | **BofA new API: 12-month consent expiry**; old items being force-migrated Mar-Oct 2026 |
| Personal fit | "independent developers" | Built for personal use | "most appropriate for hobbyist use"; approval questionnaire |
| Auth model | mTLS cert + token | Access URL with embedded credentials | client_id/secret |

Sources: SimpleFIN https://beta-bridge.simplefin.org/ ,
https://beta-bridge.simplefin.org/info/developers (24 requests/day expected, 90-day request window),
https://actualbudget.org/docs/advanced/bank-sync/simplefin/ (MX upstream, daily update).
Plaid https://plaid.com/docs/account/billing/ (Trial: teams created on or after 2026-04-15, 10
Production Items, Transactions included), https://plaid.com/docs/changelog/ (Trial plans; BofA
migration and 12-month consent; 2026-05-20 OAuth access).

**Ranking for LEGION:** Teller first (free, real-time API, webhooks, per-transaction ids). SimpleFIN
second (cheap, built for this exact use, but MX-backed, daily only, 90-day window, BofA unverified).
Plaid third (10-Item lifetime cap and a known BofA 12-month consent expiry; it is at least the one
source that states its BofA re-auth period in writing).

## 7. Fit with LEGION, without designing the code

- **No printed anchor.** A Teller pull states no total, opening or closing balance for a statement
  period, so CLAUDE.md §4 rules 2-6 cannot pass it. It falls to **rule 7**: extraction is JSON
  parsing (deterministic, condition 1 holds), every row `UNRECONCILED`, said in words, and deleted
  when a gated BofA statement for the same account and dates commits. Same posture as today's BofA
  activity CSV. [reasoned]
- **Rule 8 (keep the evidence):** the `balances.ledger` reading and each posted row's
  `running_balance` are things Teller stated and should be stored as received. They are one source
  checking itself, so they must **never** be promoted to a gate anchor (rule 6's shape). [reasoned]
- **Pending churn** (id sometimes replaced, date shifts) means a pending row must be replaceable,
  never treated as committed; the 7-10 day re-pull window is Teller's own instruction. [confirmed advice, reasoned fit]
- **Third-party content rule (§7) does not bite:** bank transactions are Kevin's own records he
  chose to import, like the statements. [reasoned]
- **Hands path / voice:** re-auth after `enrollment.disconnected` needs Kevin in a browser with his
  phone for 2FA. LEGION should say in words that the bank feed is disconnected and since when.
  [reasoned]
- **Clone-and-run:** each household signs up for its own Teller app and certificate; nothing
  Kevin-hosted is shared. Fits ADR 0044 / §7. [reasoned]

## Open risks

1. Sign-up route not found; `/signup` is 404. [unconfirmed]
2. `api.teller.io` unreachable from this laptop; retest from Cloud Run. [unconfirmed]
3. BofA enrollment lifetime unpublished; BofA's new aggregator API carries a 12-month consent for
   Plaid, and Teller's exposure to the same is unknown. [unconfirmed]
4. Amount sign per account type undocumented. [traced only]
5. History depth undocumented. [unconfirmed]
6. Free-tier rate limits undisclosed; a 429 must back off, not fail the day. [confirmed]
7. Teller's public footprint is thin: no blog since 2023, no changelog, no status page. Alive, but
   quiet. Keep the Playwright CSV path (`tools/bofa_pull.py`) runnable as the fallback until Teller
   has run clean for a full statement cycle. [reasoned]
8. Terms allow termination "at any time, with or without cause". [confirmed]
9. Certificate lifetime undocumented; a silent cert expiry would look like an outage. [unconfirmed]
