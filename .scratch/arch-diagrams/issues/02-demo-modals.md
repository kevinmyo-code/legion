---
map: arch-diagrams
ticket: "02"
title: "Diagrams and the resume open over the page; diagrams first; no second greeting"
type: build
status: built
status-detail: "built 2026-11-05 on feat/demo-modals: server suite, vitest, tsc green; shots at 390 and 1440 wide looked at. Owed: engine deploy (demo migration 0008), load_recruiter_content with resume.png, one /try run on the Android phone"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Diagrams and the resume open over the page; diagrams first; no second greeting

From Kevin's own `/try` test on 2026-11-05:

- *"the diagrams should kinda like pop up over the whole page as a modal i think, right now it
  shows in chat but too small. but when i click to zoom its too zoomed in. so a pop up fit to page
  modal works better"*
- *"some questions didnt show diagrams at all"*
- *"asking for the resume should pop up a modal thats scrollable showing my 1 page resume"*

## What was built

**One modal for the page** (`frontend/src/components/assistant/cards/presenter.tsx`), mounted
inside the assistant on `/try` and in the app shell (`assistant-modals.tsx`). The newest
`diagram` or `resume` card opens it by itself; while it is open a newer one replaces what it shows
in place (a new view, new highlights). Closes on the X, Esc, a backdrop tap and the phone's back
gesture (one history entry, pushed with the next `__TSR_index` so TanStack Router reads it as a
BACK). The chat keeps a small thumbnail (a diagram) or a line (the resume) that opens it again.

**Fit to the page** (`fit-view.tsx`): a diagram is scaled to the modal's width and height,
aspect kept, from its `viewBox`; the resume page is scaled to the width (capped at 880 px on a
desk) and scrolls. Zoom (buttons, pinch, ctrl or cmd + wheel) multiplies the fitted size; Fit
puts it back. Highlight outlines use `non-scaling-stroke`, so they stay 3 px at any fit. A wide
diagram on an upright phone says to turn it sideways or zoom.

**The voice keeps running.** The modal never touches the client. A modal dialog makes the page
under it inert, so the voice controls ride in the modal's foot (`ModalVoiceBar`): status words,
Stop, Interrupt, Talk, the same client calls as the sheet and the dock.

**Diagrams first** (server). `assistant/prompt.py` `DIAGRAM_RULE`, placed before the about_kevin
and legion_docs rules: any question about how a project works or is built, its architecture,
design, data model, deployment, what runs where, a flow, going a level deeper, what Kevin did on
a project or job, or how he builds with AI agents, gets `show_diagram` first, closest view, then
the walkthrough. It names no project, so a version is never told of one it withholds. The tool's
description says FIRST and maps phrasings to views (`diagrams/catalogue.py` `ASKS`), filtered with
the views by `declared_for`. `legion_docs` now also picks the view from the question's own words
(`QUESTION_VIEWS`), before the passages.

**`show_resume`** (`demo/tools.py`), recruiters only like `about_kevin` (not in the registry, not
on `/mcp`, in `RECRUITER_TOOLS` and `RECRUITER_ONLY_TOOLS`). Its `resume` card carries links, never
bytes: `/api/demo/asset/resume_image` (the page as a PNG, because Android Chrome draws no PDF in a
page) and `/api/demo/asset/resume` (the download). A version with no resume (LinkedIn) is refused
in words and draws nothing. The PNG is `RecruiterContent.resume_image` (migration
`demo/0008`), loaded from `resume.png` beside `resume.pdf` by `load_recruiter_content`, which
refuses a non-PNG and a PNG without its PDF. Kevin's private generator renders it with pymupdf.
The page's Experience section gains "See the resume", the hands path to the same modal (ADR 0035).

**No second greeting.** The five-minute cap ends a recruiter conversation; the next one used to
send the opener again, so Alfred greeted and gave the recording notice twice. The page now sends
`CONTINUE_TURN` for a later conversation on the same page (with the visitor's last question), and
`RECRUITER_CONTINUE_RULE` tells the model not to greet again.

## Why some questions showed no diagram (Kevin's prod visit log, 2026-11-05 23:58 to 00:04 UTC)

| Line | What ran | Tag |
|---|---|---|
| "Go one level deeper. What runs where?" | legion_docs only, no diagram | traced (prod log) |
| "What did he do at Quanex?" | about_kevin only, no diagram | traced (prod log) |
| The other nine lines | show_diagram fired | traced (prod log) |

- Typed and spoken turns share one Live session and one declaration list; there is no separate
  text path, so show_diagram is declared on both. traced (code)
- The old frame sent "how LEGION is built" to legion_docs and "his other projects" to about_kevin
  before it mentioned the diagram, and named no follow-up phrasing ("deeper", "what runs where")
  or a job ("what did he do at"). reasoned as the cause of the two misses: nothing in the prompt
  or the description tied those phrasings to show_diagram.

## Verification

| Step | State |
|---|---|
| pytest: `tests/test_demo_modals.py`, the recruiter and diagram suites, full server suite | done |
| vitest (`presenter.test.tsx`, `cards.test.tsx`, `live-client.test.ts`), `npx tsc -b` | done |
| `openapi.yaml` and `schema.d.ts` regenerated | done |
| Playwright `e2e/modals-shots.spec.ts` at 390 x 844 and 1440 x 900: opens by itself, Stop works inside it, updates in place, Fit and zoom, back closes it at `/try` | done |
| Engine deploy (demo migration 0008), then `load_recruiter_content` with `resume.png` | owed: Kevin |
| One `/try` run on the Android phone in Chrome: a diagram opens fitted, back closes it, the resume opens and zooms, the talk keeps going | owed: Kevin, on-device |
