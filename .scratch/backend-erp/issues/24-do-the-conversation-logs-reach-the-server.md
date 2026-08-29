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


## RULED 2026-08-29 (Kevin): the conversation audit goes to the server.

*"same for conversations for audit."*

Schema landed in `20260829000100_obd_samples_and_conversation_audit.sql`, UNAPPLIED. 197 rows today,
so volume is a non-issue.

**Why this is safe, restated as the reason rather than a hope:** the read-through rule is satisfied
at the SOURCE. `ConversationAudit`'s redaction is per-ROW and happens at WRITE - an
`EPISODIC_EXCLUDED_TOOLS` result is replaced before insert while the fact the tool ran survives - so
the phone's table never held the protected content and neither can the server's. Section 7's own
words: the guarantee is *"that it was never stored, not that something remembered to exclude it."*

**Identity: `(device_id, local_id)`.** Unlike a telemetry sample, a conversation row is not a shared
fact - it is what ONE phone heard and said. Two phones can both hold turn 41 and both are real, so
the key carries which device produced it and `turn_seq` stays an ordinary column for regrouping.

### The three checks this ticket listed are still owed, and one is now urgent

1. **Is every currently-excluded tool's result actually redacted before insert?** The doc comment
   names the mechanism; confirm it against `EPISODIC_EXCLUDED_TOOLS` as it stands TODAY. A tool added
   since that comment was written is the gap, and it is now a gap that reaches a server.
2. **Do `Kind.USER` rows contain third-party content by transcription?** Kevin reading an email aloud
   is his own words by the letter of the rule and someone else's content in substance. **This needs a
   ruling before upload, not after** - it is the one case where the source-side guarantee does not
   cover what actually lands.
3. **Retention.** A phone-local log is bounded by the device; a server-side one is not. 197 rows is
   nothing, but nothing is what every large table starts as.

### And one thing the schema change forces

**`engine/DeviceId.kt`'s doc comment becomes false.** It states the value *"never leaves the device,
and nothing here transmits it anywhere"* - true while it only scoped widget layouts, false the
moment a conversation row carries it. ANDROID_ID going to the household's own project is fine; the
comment claiming it does not is not. **Correct it in the same commit that builds the upload**, or it
joins `EventReplicaDao.upsert` and `GeneratedFormScreen`'s "PHOTO ON FILE" on the list of comments
this repo believed until it checked.

## RULED 2026-08-29 on the USER-row question: upload them as they are.

Delegated ("go per ur recommendations"). This is the check the ticket said was owed **before** the
upload exists, so here it is before the upload exists.

**The question:** a `Kind.USER` row is never redacted - the user's own words are his. But Kevin
reading a message aloud puts someone else's content into a USER row by transcription. Does §7's
read-through rule reach it?

**No, and the reason is what §7 is actually about.** That rule governs content **the app fetched on
his behalf** - mail first, and anything of that shape later. Its mechanism is
`LiveToolbox.EPISODIC_EXCLUDED_TOOLS`, applied at the write sites, and its stated guarantee is that
such content *"was never stored"*. The app is the thing doing the fetching, and the rule stops the
app from building a durable store of other people's messages.

**A person speaking is not the app fetching.** Kevin saying a sentence out loud is his own utterance,
whatever it quotes. Treating a transcript of his speech as third-party content would mean the app
must decide, per sentence, whose words those originally were - which is not a judgement any code can
make, and pretending otherwise would produce a rule that is either useless or wrong.

**And the upload changes nothing about who can read it.** The destination is the household's own
Supabase project, visible to exactly the two people ticket 02's RLS already grants - all rows, no
roles. Nothing crosses a boundary it was not already inside. That is the same reasoning ADR 0038
used to accept a cloud system of record at all.

**What stays binding, and is not weakened by this:**
- **Tool results keep their redaction.** That is the mechanism that satisfies §7, it happens at write
  on the phone, and it is exactly why this table is safe to sync. Nothing here touches it.
- **Check 1 of this ticket is still owed and is now the load-bearing one**: confirm every tool
  currently in `EPISODIC_EXCLUDED_TOOLS` really is redacted before insert, as it stands today rather
  than as the doc comment described it when written. A tool added since is the gap, and it is now a
  gap that reaches a server.
- **Retention is still unanswered.** 197 rows is nothing; nothing is what every large table starts as.
