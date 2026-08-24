---
status: accepted
decided: 2026-08-24
decided-by: Kevin
source: "[[decisions#2026-08-24 - The cutover arc: five decisions in one day]]"
tags: [adr]
---

# 36. Platform encryption accepted; SQLCipher rejected

## Standing

Room's SQLite database (`legion_database`) is **not** application-level encrypted. Android's own
file-based encryption (FBE, on by default since API 24) covers the powered-off/lost-phone case,
and the Android Keystore already guards the one secret worth guarding separately - the Gemini API
key (`ai/KeyVault.kt`, AES/GCM). That combination is treated as sufficient. SQLCipher was
evaluated and rejected.

## Context

`.scratch/hardening/issues/04-encryption-at-rest.md` asked the question deliberately rather than
by omission: is an unencrypted on-device database an oversight or a decision? The threat model
LEGION is built for is stated repeatedly elsewhere in this repo (CLAUDE.md §1, §2) - **two adults,
BYO everything, no tenancy, no roles.** There is no third party the database needs to be opaque
to; the phone itself is the trust boundary, and Android already encrypts the phone at rest.

SQLCipher was the real alternative, not a straw man. Adopting it has a genuine, ongoing cost this
codebase has repeatedly relied on paying: **the pulled-DB debugging workflow.** Multiple real bugs
in this project (the mileage-recall mystery, a category-mapping error, a merchant-key regex
swallowing dates, a rows-vs-totals mismatch worth a five-figure sum) were only found by running
`adb exec-out run-as com.kevin.legion cat databases/legion_database` and reading the raw SQLite
file directly - a workflow SQLCipher does not break outright but meaningfully taxes (a passphrase
has to be threaded through every ad hoc read, and every Room/WAL tooling path used elsewhere in
the codebase gets slower and more fragile against it). There is also a real, if smaller, runtime
performance cost on every query.

## Decision

**Accept platform encryption (Android FBE + Keystore) as sufficient. Do not adopt SQLCipher.**
Kevin, 2026-08-24: *"accept platform encryption."* FBE covers the case that matters (a lost or
stolen powered-off phone), Keystore already covers the one secret with a materially different
sensitivity profile (a credential, not personal data), and SQLCipher's cost buys nothing against
a threat model with no untrusted third party in it.

## Consequences

- The pulled-DB verification workflow this project depends on for real bug-finding stays exactly
  as cheap as it is today. This was a deliberate, named factor in the decision, not an
  afterthought.
- If the threat model ever changes - a third adult, a shared device, cloud-hosted storage instead
  of on-device - this ADR is the one to revisit first, because its premise (no untrusted party
  with access to the file) is what the acceptance rests on.
- Keys stay the one thing encrypted above the platform floor. `ai/KeyVault.kt` is unaffected by
  this decision and is not weakened by it.
