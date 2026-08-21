---
map: location-intelligence
ticket: 7
title: "TomTom's caching clause, before anything is stored"
type: research
status: resolved
status-detail: "2026-08-21 - no storage at ALL, and a licence problem bigger than the question asked"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# TomTom's caching clause, before anything is stored

## Question

`.scratch/hands-and-senses/research/14-location-intel.md` marked TomTom's caching posture
`unverified`, and flagged that Google's equivalent clause is the most restrictive in the market with
only a narrow 30-day lat/lon carve-out.

**This blocks storing an ETA, not calling for one.** [Ticket 06](06-departure-advisor.md) can ship
computing a fresh ETA each time; what it may not do until this is answered is persist one to Room or
to Drive `appDataFolder`.

Establish, from TomTom's own terms:

1. May a Routing API response be cached at all, and for how long?
2. Are coordinates, ETAs and route geometry treated differently?
3. Does storing an ETA in an app-private database count as caching under their terms?
4. Anything about attribution requirements when a result is spoken aloud rather than displayed -
   decision 4 already says "per TomTom", so confirm that satisfies it.

Quote the clauses verbatim with URLs. If a term is genuinely ambiguous, **say so rather than
choosing the convenient reading** - the safe default is not to persist.

## Answer - 2026-08-21. Two findings, and the second is bigger than the question.

Governing document: **TomTom Portal Terms & Conditions**,
https://docs.tomtom.com/legal/terms-and-conditions (both `developer.tomtom.com/terms-and-conditions`
and `/legal` 301 there). The consumer T&C explicitly disclaims API use.

### 1. An ETA may not be stored. For any duration. `[official-terms]`

Clause **11.4** opens as a prohibition with one carve-out:

> The caching or storing of any Results shall be prohibited except that you may cache Results ...
> **11.4.1.** only ... where the control headers are present in the Result;
> **11.4.2.** ... not ... longer than the maximum age period indicated in such cache control headers

And the Routing API's documented response headers close it: **`Cache-Control: no-cache, no-transform`
and `Pragma: no-cache`. No `max-age` is emitted at all.** 11.4.2 caps retention at the indicated
maximum age, and the indicated maximum age is nothing. **Permitted retention: zero.**

**"Results" covers an ETA exactly as it covers a polyline** - one flat definition, "geocodes and
reverse geocodes, map data tiles and route information", with no tiering. There is no
"a derived number is fine" allowance of the kind Google and HERE grant. Clause **11.6.1**
independently forbids using the content "for the creation of any secondary or derived database",
which is what a Room table of accumulated ETAs is.

**On-device is not a loophole - it is the clause's own subject.** 11.4.1 says "cached in **clients**";
the phone *is* the client. Drive `appDataFolder` is worse: off-client persistence falls outside the
client-cache carve-out entirely.

The one honest ambiguity, flagged rather than resolved: in HTTP, `no-cache` means revalidate before
reuse, not `no-store`. A permissive reading says a header IS present so 11.4.1 is satisfied. **That
reading still fails 11.4.2** - there is no positive max-age to bound retention - and a prohibition
with an unquantifiable carve-out does not open.

### 2. A free Evaluation key does not license running the app. `[official-terms]`

This was not the question and it matters more than the answer.

> **"Evaluation Use"** means **internal evaluation and testing by you** of the Licensed Products.
> **2.2.** TomTom grants ... a limited ... revocable and royalty-free license ... **for Evaluation
> Use only.**

The paid path does not obviously rescue it: clause 2.1's grant applies to a **"Permitted Solution"**,
defined as an application "licensed to end users and which includes **Asset Management
Functionality**." A personal voice assistant is neither. 2.1 also excludes "Automotive Usage",
undefined precisely, and an OBD-connected in-car surface edges toward it. `[not-established]`

Genuine ambiguity, stated rather than smoothed: the pricing page advertises "Free monthly requests
per API, no credit card needed" (20K/month Routing) **without calling it Evaluation Use**, while 8.4
refers to an "Evaluation Use Subscription Plan". **How the advertised free tier maps onto clause 2's
two grants is not stated in either document.** Also, 23.1 lets TomTom terminate Evaluation access
"at any time without notice."

### 3. "Per TomTom" is not established as sufficient attribution. `[official-terms]` / `[not-established]`

Clause **17.3**: for services that do not auto-generate a logo - Routing does not - "you agree to
implement to the TomTom Copyright API in order to generate the applicable copyright **and logo**
attribution." **A logo cannot be spoken.** The Copyrights API is documented as serving "the Map
Display services" and contemplates neither Routing nor a voice surface. 17.3's first sentence also
makes any use of the TomTom name subject to prior written approval, so "per TomTom" is arguably
gated rather than a safe harbour. **No voice-only attribution path exists in the document.**

## What this changes on the map

- **Settled decision 4's guardrail was right and is now binding rather than precautionary:** no ETA
  is persisted to Room or to Drive, ever. Not "until we check" - the check came back zero.
  Request-time use and immediate discard is the only supported posture.
- **[Ticket 06](06-departure-advisor.md) is unaffected in shape**: it computes a fresh ETA inside its
  window and discards it. It must not accumulate a history for a learned prep buffer - which is a
  second, independent reason [decision 15](../map.md) chose a global buffer.
- **Findings 2 and 3 are Kevin's to rule on**, not mine. They question whether TomTom is usable at
  all, which is bigger than this ticket's scope.
