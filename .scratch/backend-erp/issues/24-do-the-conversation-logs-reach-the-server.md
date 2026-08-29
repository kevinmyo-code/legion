---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The conversation audit is phone-local, and the PC cannot see it

**Noticed 2026-08-28 while mapping what the general client should read. Not urgent, and worth
deciding before the web app grows an audit screen someone assumes already works.**

## What exists, and it is good

`data/local/ConversationAudit.kt` records every exchange with the assistant - `conversation_audit`,
grouped per turn, with `ConversationAuditExport` alongside it. Kevin: *"we also record the
conversations with the ai so we can audit the logs later too."*

**Its redaction is the interesting part and it is already right.** Read-through redaction is
per-ROW, not per-turn: a tool in `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` returns content other people
wrote to Kevin, so its RESULT is replaced while the fact that the tool ran survives - because the
whole point is knowing which tool ran. `Kind.USER` rows are never redacted; the user's own words are
his.

## The question

**Should `conversation_audit` reach Supabase, so the PC can audit it?**

The reason to want it: auditing a voice assistant's behaviour over weeks is exactly the kind of
read-heavy, wide-screen work ADR 0040 says belongs on the general client. Doing it on a phone screen
is miserable.

The reason it is not automatic: **CLAUDE.md section 7's read-through rule.** Anything other people
wrote to Kevin is used to answer and then dropped - never persisted, never synced, never remembered.
A conversation log is precisely where such content would otherwise accumulate.

## Why this is probably safe, and why it still needs a ruling rather than an assumption

**Redaction happens at WRITE, not at read.** By the time a row is in `conversation_audit`, an
excluded tool's result is already gone - the table never held it. So syncing the table cannot leak
what the rule protects, because the rule was enforced one layer earlier.

That is a real property and not a lucky one: section 7 says the guarantee is *"that it was never
stored, not that something remembered to exclude it."* This design already satisfies that.

**But it must be checked, not assumed**, and specifically:

1. **Is every excluded tool's result actually redacted before insert?** The doc comment says per-row
   and names the mechanism; confirm it against `EPISODIC_EXCLUDED_TOOLS` as it stands today, not as
   it stood when written. A tool added since would be the gap.
2. **Do `Kind.USER` rows contain third-party content by transcription?** Kevin reading an email aloud
   is his own words by the letter of the rule and someone else's content in substance. Worth a
   ruling either way rather than discovering it in a log.
3. **What is the retention?** A phone-local log is bounded by the device. A server-side one grows
   forever on a free tier, and ADR 0038's storage estimate did not include it.

## Not blocked, and deliberately low priority

Nothing depends on it. It is filed so that when the web app grows an audit view, whoever builds it
finds this rather than assuming the table is already there.
