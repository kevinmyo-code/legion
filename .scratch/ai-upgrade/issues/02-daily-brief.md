---
map: ai-upgrade
ticket: "02"
title: "Build: the daily brief, built at 5am on the server, opened from a Home button"
type: build
status: open
blockers: ["01"]
blocked-by: ["[[01-what-to-upgrade]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: the daily brief

Kevin, 2026-10-22: *"a personal daily digest delivered to my phone. canvas, money, car, ai news +
topics im interested in, a morning brief basically. it should be a button, pressing it opens a modal i
can dismiss. so i can bring it back up anytime. updates daily."*

## Rulings

1. **Built once a day at 05:00 in the household's time zone** by a server job (`manage.py
   morning_brief`, wired through Cloud Scheduler like `canvas_poll`), plus a Refresh inside the modal.
   It runs on the household's existing Gemini key.
2. **Never pushed.** No notification and no unsolicited speech. The brief is opened from a button on
   HOME and shown in a dismissible modal that can be reopened at any time. This matches
   hands-and-senses ticket 32 ("sitreps stay tap only or via voice"), and the `get_sitrep` voice tool
   reads the same brief.
3. **Sections:**
   - **Canvas:** tasks due in the next 7 days, from `events` (kind task), with the submitted state as
     Canvas reported it. If the Canvas feed is stale or needs a login, the brief says so in the
     freshness sentence's own words.
   - **Money:** yesterday's spend (total plus top 3 merchants); month to date against the same day
     last month; bills due in the next 7 days from recurring charges. **If no recurring-charge source
     exists, say so in words and do not guess.** Any figure built on `UNRECONCILED` rows is labelled
     unverified (§4 rule 7).
   - **Car:** whatever the engine holds (maintenance due, last drive, OBD rollup). A source that has
     nothing says so ("not logged", never 0).
   - **News:** one Gemini call with Google Search grounding over these topics: AI news, AI
     companies and new models, software engineering and tech news, cybersecurity, and Android and
     dev tools. 5 to 8 bullets, each with its source link.
     **AMENDED 2026-10-23 (Kevin):** when grounding ties no bullet to a source, the bullets still
     ship with no link of their own, and the section's sources are listed once on a trailing line
     that says in words they are for the section, not per item. A bullet is never shown with a link
     that grounding did not tie to it.
4. **Deterministic sections are composed by code. Only News reaches an LLM**, the same split
   `SitrepBuilder` already uses.
5. **Read-through (§7) holds.** The stored brief carries web news only. Gmail newsletter summaries
   (the phone's existing sitrep NEWS module) are never stored server-side. That module stays live on
   the phone if it is enabled.
6. **Storage:** a tenant-scoped `briefs` table listed in `TENANT_TABLES`, holding the last 14 days.
   API: `GET /api/brief/latest` and `POST /api/brief/refresh`. The phone caches the latest brief for
   offline use and says in words how old it is.
7. **Phone:** the existing HOME sitrep card becomes the Brief button. The modal shows the server
   sections, followed by the phone's live calendar and weather sections.
8. **Cost** is logged per run. Estimate: well under $1 a month (one flash-lite call with grounding a
   day). Grounding's free allowance on Gemini 3.x is unverified.

## Verification

Server suite green; a `--dry-run` of `morning_brief` printed against live data. The phone build
waits for dumb mode (one Gradle writer at a time): compile, full suite, then an install. A real 05:00
run is owed on the device.
