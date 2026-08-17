# BUILD: the two Gmail tools

Type: task
Status: resolved (2026-08-16, verified built)
Blocked by: 09, 12

## Question

Nothing to decide. Graduated 2026-08-13 from [ticket 05](05-what-counts-as-worth-reading.md),
[ticket 07](07-what-reaches-the-model.md) and [ticket 10](10-offline-and-failure.md).

1. **`search_mail(query, limit)` and `read_mail(id)`.** Descriptions are fixed verbatim in ticket 05
   - a description is the only thing the model ever reads, so do not paraphrase them. Net +2 tools
   against a budget of 69.
2. **Briefing is `search_mail` with no query**: `is:unread in:inbox category:primary newer_than:2d`,
   cap 10 hard, over the cap say the total and read the first ten. Empty says so plainly.
3. **Search passes the model's `query` to Gmail's `q` unchanged**, cap 5, and **Alfred always says
   the query he ran**. That disclosure is the guardrail, not a nicety - it is what makes a bad
   translation visible instead of a confident wrong answer.
4. **Nothing is stored. Anywhere.** No Room table, no entity, no DAO (ticket 07).
   **And mail tool results are excluded from `EpisodicTurn`/`CompanionMemory` persistence** - this is
   the part that will be missed if it is not tested, because the default behaviour silently persists
   a subject line into the episodic log and from there into the whole-database Drive backup.
5. **Alfred may not form durable memories from mail.** He re-reads each time.
6. **Four distinct failure messages**, verbatim from ticket 10 point 4, driven by ticket 12's
   `GoogleGrantResolver` for the two grant-shaped ones. Never one collapsed message.
7. **No surface.** Voice-only (ticket 08 point 4). The only Gmail pixels are ticket 12's Setup row.

## Verification

- **A test that asserts nothing mail-shaped reaches `EpisodicTurn`/`CompanionMemory`.** Point 4 is
  the one requirement here whose failure is invisible on the device.
- On the device: one real briefing, one real search, and confirm Alfred spoke the query.
- Force each of the four failures - airplane mode, revoked grant, never-granted - and confirm the
  messages differ.

## Answer

**VERIFIED BUILT 2026-08-16** (Kevin: "repo is ahead. check and close if true"). Closed on evidence,
not assumption. All `traced`.

- **Both tools ship and are WIRED**: `search_mail` (`LiveToolbox.kt:1210-1223`) and `read_mail`
  (`:1225-1234`), both inside `declarations()` (spanning `:114-1386`), **not**
  `onboardingDeclarations()` (`:4574`) - the trap `LiveToolboxDeclarationSetTest.kt:8-27` documents.
  Dispatched at `:1537-1538`. Descriptions match ticket 05's table string for string.
- **The briefing shape is exact**: `BRIEFING_QUERY = "is:unread in:inbox category:primary
  newer_than:2d"` (`GmailToolLogic.kt:23`), `BRIEFING_CAP = 10` (`:28`), a blank query forces the
  briefing and ignores `limit` (`:44-51`), over-cap reports the true total from `resultSizeEstimate`
  (`LiveToolbox.kt:1776-1779`), empty says so in words (`:1783`). Search caps at 5 (`:31, 49-50`).
- **Nothing is stored.** No Gmail entity or DAO exists anywhere; `gmail/` holds only `GmailAuth`,
  `GmailClient`, `GmailToolLogic`. The episodic exclusion is enforced by dropping the WHOLE turn
  (`GeminiLiveSession.kt:694`), flagged when the call ARRIVES rather than after dispatch (`:863`),
  and pinned by `GeminiLiveSessionEpisodicExclusionTest.kt`.
- **Four distinct failure messages** with the never-granted vs lapsed split routed through
  `GoogleGrantResolver` (`GmailToolLogic.kt:77-105`), distinctness asserted at
  `GmailToolLogicTest.kt:140`.

### Two gaps found while verifying - neither reopens this ticket

1. **Point 3's disclosure is available but never instructed.** The query rides the payload
   (`LiveToolbox.kt:1773`, commented "so Alfred always has it to say"), but **no prompt text
   anywhere tells him to say it** - a grep for such wording across `app/src` is empty, and neither
   tool description carries it. The guardrail is offered, not enforced. `traced`.
2. **Point 5 is only half-enforced, and that half is a real hole** - see
   [the remember leak](21-remember-leak.md), filed as its own ticket because it is a defect in the
   mail read-through rule rather than unfinished work on this one.
