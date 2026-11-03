---
map: claude-session
ticket: "09"
title: "Build: HTML delivery, a researched page from the Alienware opened on the phone"
type: build
status: built
status-detail: "Built 2026-11-03 on integrate/claude-session-on (Kevin: \"add the html delivery before we test so we can test everything at once\"). Engine, worker, phone and web desk tests green. Owes: the engine deploy and the Atlanta run on the A25 (ticket 07, step 9)."
blockers: ["03", "04", "05"]
blocked-by: ["[[03-engine-app]]", "[[04-worker]]", "[[05-phone]]"]
open-blockers: 3
ready: false
tags: [ticket]
---

# Build: HTML delivery

Kevin, 2026-11-03: *"i ask alfred to make me an itinerary for a day trip into atlanta, alfred asks
if he should call the claude session on my alienware, he calls it asks it to research and make the
html, the claude session somehow delivers it to the phone... push the html file to legion"*.

## Rulings (Kevin's defaults, 2026-11-03, may be revised)

1. Alfred ASKS before opening a delivered page ("want me to open it?"); a yes, the very next thing
   said, opens it. A notification tap does the same.
2. Delivered pages also show on the web desk under their task (`/claude`).
3. 30-day retention, like every other Claude session row (the task's prune cascades).
4. The work laptop never delivers a page (ruling 5 and 18 stand: summary only).

## What was built

- **Engine** (`server/claude_session/`): `deliver: phone` joins `email_self`
  (`migrations/0002_html_delivery`). Refused for a `listed` machine on create and assign, and an
  unaddressed page task is never claimed by one. At finish a page task's `.html`/`.htm` found files
  are requested at once (asking for the page was the yes); any other found file still waits for a
  yes. Upload of a page: `text/html` only (415), 5 MB (413), its `<title>` kept. Every task row
  lists arrived pages under `artifacts: [{id, filename, title, size, url, expires_in}]`; `url` is a
  relative `/api/claude/pages/{id}/?t=<signed>`, minted per read, valid 10 minutes, naming one
  household and one file. `PageView` needs no token header and serves the page with the recruiter
  report's headers (`Content-Security-Policy: sandbox allow-scripts`, nosniff, `private,
  no-store`): its scripts run in an opaque origin and reach no cookie and no engine route.
- **Worker** (`tools/claude_session/`): a `deliver: phone` task's message tells the helper to write
  ONE self-contained HTML file and list it with `--found`; the worker uploads it straight after the
  finish (the heartbeat asks again if the engine was down). A `listed` machine says it cannot.
- **Phone**: `claude_session` gains `page: true` and `open_page`; the offer clause offers the
  Alienware for research to write up. The result waits up to 3 minutes for the page, then says
  "The page \"<title>\" is ready. Want me to open it?" (or that it did not arrive). `open_page`,
  the notification tap (`ClaudePageActivity`) and the screen's Open page button share
  `ClaudePageOpener`: it re-reads the task for a FRESH link and opens it in LEGION's browser, and
  says "opened" only on a loaded page. The screen's send box has "As a page on my phone".
- **Web desk**: `/claude` (desk rail "Claude") lists tasks with status in words and an Open page
  link per artifact; refetched every minute so a link is never stale. Hidden on a recruiter visit.

## Verification

- [x] `server/tests/test_claude_session_pages.py` (11), with the rest of the suite.
- [x] `tools/claude_session/test_worker.py`: page asked for, uploaded at finish; no page without it.
- [x] `app/.../claude/ClaudePageDeliveryTest.kt` (7): deliver phone, work laptop refused, ask and
      hint, the 3 minute wait, open_page opened only on a load, no page opens nothing.
- [ ] On the A25 after deploy: ticket 07 step 9 (the Atlanta day trip).
