---
map: recruiter-demo
title: "Recruiters try LEGION: one shared invite code, a fake household, a guide that greets them"
charted: 2026-11-02
charted-by: "Kevin + Opus"
effort: "`.scratch/recruiter-demo/`"
tickets: 12
open: 12
status: open
tags: [map]
---

# Recruiters try LEGION

**Kevin, 2026-11-02:** *"we display data governance skills and user authentication skills and give
recruiters a key with my resume when i apply to their jobs. they use that key to query my legion
engine, but as a recruiter user type so sensitive data doesnt show"* ... *"fake demo household. global
cap of $5. ill be sharing the same key to different recruiters"* ... *"legion should know its a
recruiter from the key, and so on connect it would tell users like > welcome to kevin's voice app ...
'i can do xxx i can do yyy' what would u like to try today"*

## Destination

A recruiter opens one link from Kevin's resume, types (or follows a link carrying) the shared invite
code, and inside a minute is talking to LEGION by voice or text. The guide greets them, says what it
can do, and offers things to try. Everything it reads is a FAKE household. Nothing they do can reach
Kevin's household, his accounts, his machines, or spend more than $5 a day in total.

## Rulings so far (Kevin, 2026-11-02)

1. Live production engine, NOT a separate demo deploy.
2. Recruiters read a fake demo household. Not masked real data.
3. One invite code shared with every recruiter.
4. Global Gemini cap $5/day across all recruiters.
5. The engine knows the caller is a recruiter from the code; the assistant greets accordingly.
6. The guide is Alfred: same name, same voice as the phone.
7. Text chat keeps working after the voice budget runs out.
8. Quanex internship: 2026-06-01 to 2026-11-27.
9. Resume placement decided after a frontend prototype, later.
10. Recruiter is a third `HouseholdMember.role` (ADR 0068 amends ADR 0045: the first role that governs
   data, and only to NARROW it). Set on throwaway visitor users in the demo household only.

## Shape

```
resume link  /try?ref=acme
   |
   v
/try page: invite code  --POST /api/demo/enter-->  engine
                                                   - checks code, per-IP limit
                                                   - issues per-VISITOR read token
                                                     (role=recruiter, ref, 2 h expiry)
                                                   - visitor user is a member of the
                                                     demo household only
   |
   v
web assistant (existing, ADR 0056)
   - voice: engine mints locked Live token; setup built for role=recruiter
     (guide persona, greeting, recruiter tool allowlist)
   - text: same prompt + tools on the text model
   - tool calls -> /api/assistant/tool -> recruiter allowlist (default deny)
     -> run_tool, scoped to the demo household by tenancy
   - every mint / text call debits the global daily demo budget first
```

## What exists, so nothing here is invented (traced 2026-11-02 on origin/dev fa29c9f0)

- **Voice in the browser:** `server/assistant/live.py` mints a single-use ephemeral Live token (30 min,
  60 s connect window) carrying the WHOLE setup with no field mask. The browser cannot change model,
  prompt or tools. Frontend: `server/frontend/src/assistant/` (live-client, capture worklet, audio).
- **Tool door:** `assistant/views.py` `AssistantToolView` checks `surface.refusal_for(name)` before
  `engine_mcp.tools.run_tool`. So an allowlist enforced server-side already exists; today it is the
  whole registry (`surface.EXCLUDED_FROM_WEB` is empty).
- **Throttle:** per signed-in member. With one shared recruiter user it would collapse to one budget;
  hence per-visitor users/tokens (ticket 02).
- **Token scope:** `household/models.py` `DeviceToken.scope` read/write exists. Read alone is NOT
  enough: read tools that reach Kevin's mail, calendar, Drive, Spotify, device logs or (later)
  `claude_session` must be off for recruiters. Role allowlist is the fence; scope is the second one.
- **Companions:** `assistant/companions.py` keys companion by `User.first_name` (Kevin -> Alfred,
  Mia -> Dorothy). A recruiter companion slots in here.
- **Honesty and the gate:** `ingest/gate.py` (reconciliation gate, quarantine on totals that do not
  reconcile). The seed keeps one receipt that fails it, but nothing recruiter-facing mentions
  rejected receipts (Kevin, 2026-11-02: *"we dont do receipt ingestion anymore"*). Recruiters see
  honesty as verified vs UNVERIFIED bank rows. Do not promise behaviour that is not on dev.
- **Not reusable for the cap:** `monthly_spend` on `fix/headunit-logs-and-spend` is household ledger
  spend (money), not Gemini spend.

## Tickets

| # | Title | Type | Blocked by |
|---|---|---|---|
| 01 | Recruiter role and tool allowlist (default deny) | build | - |
| 02 | Invite code -> per-visitor read token, `ref`, per-IP limit, rotation | build | 01 |
| 12 | Phone's tuned turn-taking and barge-in in the browser (spike first) | build | - |
| 03 | Demo household seed (fake bank, car, receipts, one failing receipt), nightly reset | build | - |
| 04 | Global daily demo budget ($5), reserve-before-mint, "budget used today" message | build | 02 |
| 05 | Guide persona and greeting on connect, capability card, suggested prompts | build | 01 |
| 06 | Isolation tests: recruiter cannot reach Kevin's household or excluded tools, incl. prompt injection | build | 01, 02, 03 |
| 07 | `/try` page + "What this key can't see" governance page | build | 02, 05 |
| 08 | Demo videos (phone, head unit, receipt scan) embedded on the LEGION page | content | Kevin records |
| 09 | Resume page + Quanex text | content | Kevin: page location, dates |
| 10 | `about_kevin`: recruiters ask about Kevin (experience, location, work authorisation) from a profile he writes | decision | Kevin writes the profile |
| 11 | Music: "play X" by voice, plays in the page via Spotify embed (not App Remote, not Kevin's account) | build | 01 |

## Rules that hold across every ticket

- Kevin's real data never reaches a recruiter session. Isolation is enforced by tenancy + allowlist on
  the server, never by prompt wording.
- No Gemini key or engine token in the browser beyond the per-visitor read token.
- Engine deploy is Kevin's call. Nothing deploys from this map without his OK.
- Quanex text (ticket 09): Kevin sees the full text before anything publishes. Public tool names,
  patterns, role, generic outcomes only. No figures, names, internal systems, screenshots, incidents.
