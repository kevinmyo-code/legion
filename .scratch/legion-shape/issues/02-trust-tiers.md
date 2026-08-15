# How many kinds of truth does the record hold?

Type: grilling
Status: resolved (2026-08-06, Kevin)
Blocked by: 01

## Question

"I did 5 sets of squats at 225" has no receipt and no total. Nothing can check it. A bank statement
can be checked against its own printed total. Does the record treat these the same?

## Resolution

**Two tiers, and they never mix.**

| Tier | Means | Examples |
|---|---|---|
| **Proven** | An outside source agrees | A statement reconciled against its own printed total; a receipt's printed total |
| **Reported** | You said so | A logged workout, a bodyweight, a voice-logged meal, an estimated macro, a spend category |

Rules, all three binding:

1. **Every stored row records which tier it is in.** Not derivable later - stored.
2. **Every figure built from both tiers says so**, in words, on every surface that renders it -
   never by colour or an icon alone.
3. **A reported fact is never promoted to proven.** Only an outside source can do that, and when one
   arrives it supersedes rather than upgrades (the pattern `deleteSupersededProvisional` already
   implements for card CSV rows).

**This already exists and was not recognised as general.** `IngestMethod.UNRECONCILED` and
CLAUDE.md §4 rule 7, both written 2026-08-06 for Bank of America's card CSV, ARE the reported tier.
Rule 7's four conditions - deterministic extraction, tagged, said in words everywhere, superseded
when a gated source covers it - were written for one CSV and describe the whole record.

**The cost, accepted knowingly:** every screen and every spoken answer carries the label, forever, on
every feature. **The alternative was worse:** a bodyweight typed at 6am and a reconciled bank balance
would look equally solid. A record that cannot tell you how much to trust it is a diary with extra
steps.

## Follow-on

CLAUDE.md §4 rule 7 is currently worded as an ingestion rule about documents. It should be
generalised to the whole record, or a §4a written for reported facts that never came from a document
at all. Not done in this ticket - flagged for whoever specifies ticket 05.
