---
map: recruiter-demo
ticket: "07"
title: "Try page and the 'What this key can't see' governance page"
type: build
status: open
status-detail: ""
blockers: ["02", "05"]
blocked-by: ["[[02-invite-code-to-visitor-token]]", "[[05-guide-persona-and-greeting]]"]
open-blockers: 2
ready: false
tags: [ticket]
---

# Try page and the 'What this key can't see' governance page

## What

- `/try`: invite code box (pre-filled from the link), one line on what this is, voice button, text
  chat, videos below (ticket 08). Phone-friendly. No account creation.
- "What this key can't see": role-permission table (owner / member / recruiter vs each tool group),
  how tenancy and the allowlist enforce it, why the model cannot leak what it never receives, the
  reconciliation gate in one diagram. The data-governance showcase.

## One assistant, not two (Kevin, 2026-11-02)

*"it has to be the same [voice] that i talk to on the legion web app, the one that recruiters talk
to. same code"*

`/try` mounts the EXISTING web assistant (`server/frontend/src/assistant/`, same live-client, same
audio, same `/api/assistant/*` endpoints). No demo-only voice or chat implementation. Everything a
recruiter gets differently is decided on the server by role: tool allowlist (01), greeting (05),
household (03), budget (04). A fix or tuning to the web assistant (e.g. ticket 12) reaches both the
same day. The canned `demo.js` in the HTML prototypes is a mock for layout only.

## Open

- RESOLVED (Kevin, 2026-11-02): *"we dont use github pages. we only share the legion web app
  frontend"*. The recruiter page is a public route (`/try`) in the engine's React app
  (`server/frontend`, TanStack routes), on Cloud Run at the run.app URL until Kevin buys a domain.
  Same origin as the assistant, so the visitor session cookie just works. Layout = prototype B
  (editorial) with the Quanex case study and report mocks, rebuilt as React components.
- Cold start (~5.4 s at min-instances 0) stays as is while building; always-on is a later call.
