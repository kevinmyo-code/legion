# BUILD: the two Gmail tools

Type: task
Status: open
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
