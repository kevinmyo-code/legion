---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# Conversation audit rows can be deleted before they are ever uploaded

**Found 2026-08-29 while building the upload path, flagged by the build agent rather than left for
someone to discover from a gap in the server-side history.**

## The problem in one sentence

`conversation_audit` trims itself locally after **14 days**, and `ConversationAuditReconcile` is
**manual** - so a row that ages out before anyone taps the button is not delayed, it is **gone**.

## Why that is worse than it sounds

The whole justification for this table is auditing the assistant's behaviour *later*. Ticket 24's
case for putting it on the server was that reviewing weeks of behaviour is exactly the read-heavy
work that belongs on the laptop.

**A 14-day local trim plus a manual upload means the server never accumulates more than 14 days**,
and only the 14 days someone remembered to push. The failure is silent: nothing errors, the table
simply has holes where nobody tapped.

This is the same shape as `runFleet` being manual - which ADR 0040 already flagged as making the
laptop's view "as stale as the last tap" - except worse, because a stale view corrects itself on the
next run and a trimmed row does not.

## Options

1. **Upload before trimming.** The trim gains a condition: never delete a row that has not been
   uploaded. Honest, and it makes the local table grow unboundedly if the upload never runs - which
   is arguably the correct failure, since the alternative is losing data silently.
2. **Schedule the reconcile**, the way `sync/ScheduledBackup.kt` schedules `DatabaseSnapshot` -
   an app-lifecycle daily check with a 24h floor, no WorkManager. Precedent exists and it is proven
   in this codebase.
3. **Lengthen or remove the local trim** now that the server is the durable copy. Simplest, and it
   trades phone storage for an audit trail that is actually complete.
4. **Accept it** and say so on the screen: the audit is best-effort and only covers what was pushed.

**Recommendation: 1 and 2 together.** The trim should not delete unuploaded rows, AND the upload
should run on its own - either alone leaves a hole. Option 3 alone just moves the cliff.

## Not urgent, but do not let it sit long

197 rows today and nothing has been lost yet, because the upload path did not exist until today and
the trim has been running against a table nobody was preserving. **The first two weeks after the
upload lands are when this either works or quietly does not**, and nobody will notice the difference
without looking.
