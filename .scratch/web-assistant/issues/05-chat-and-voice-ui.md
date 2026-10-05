---
map: web-assistant
ticket: "05"
title: "The chat and voice UI: family view, workbench, and the Android chatbox"
type: prototype
status: resolved
status-detail: "Kevin picked C, the orb, opening to typing"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# The chat and voice UI: family view, workbench, and the Android chatbox

## Question

2-3 clickable HTML prototypes (Kevin's standing preference), iPhone width for Mia's family view,
desktop for the workbench, 384dp for the Android chatbox beside the existing `AssistantStrip`.

- Where the chat opens from (a button on the bottom bar? a panel?), the live-voice state (listening,
  speaking, interrupted), mic permission denied said in words, the engine down said in words.
- Session-only history: what "new conversation" looks like.
- Android: the typed box and text-only replies without disturbing push-to-talk.

## Answer (2026-10-04)

**Kevin picked C, the floating orb, with one change: it opens to TYPING, not voice.** Source of
record: `research/05-prototypes/assistant-prototypes.html`, variant C, plus its workbench (docked
right panel, "/" focuses the box) and Android (typed field beside the push-to-talk pill, typed
replies "shown, not spoken") renderings.

- Tapping the orb expands to the chat with the keyboard up; a mic button switches to live voice (that
  tap is the user gesture iOS needs). Minimising keeps the conversation with a status on the orb.
- The companion's name comes from the member's companion (Mia sees Dorothy), never hardcoded.
- Tool lines only after a success; failures say what did not happen; phone-only asks say "That's only
  on Kevin's phone"; screen lock ends it ("Conversation ended when the screen locked."); "Not saved"
  line and New conversation.
